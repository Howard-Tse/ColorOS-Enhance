package dev.howard.colorosenhance.diagnostics;

import android.annotation.SuppressLint;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Binder;
import android.os.Bundle;
import android.os.SystemClock;
import android.os.UserManager;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Process-local, batched diagnostics. Hook and frame callbacks never perform I/O. */
public final class DiagnosticReporter {
    private record Signal(DiagnosticPoint point, DiagnosticPoint.State state, long elapsed) { }
    private static final int MAX_PENDING = 96;
    private static final long MIN_INTERVAL_MS = 1_000;
    private static final Binder PROCESS = new Binder();
    private static final ArrayDeque<Signal> pending = new ArrayDeque<>();
    private static final EnumMap<DiagnosticPoint, Signal> last = new EnumMap<>(DiagnosticPoint.class);
    private static final ScheduledThreadPoolExecutor worker = new ScheduledThreadPoolExecutor(1, task -> {
        Thread thread = new Thread(task, "coloros-diagnostics");
        thread.setDaemon(true);
        thread.setPriority(Thread.MIN_PRIORITY);
        return thread;
    });
    private static boolean scheduled;
    private static int retries;
    private static BroadcastReceiver bootUnlock;

    static {
        worker.setKeepAliveTime(10, TimeUnit.SECONDS);
        worker.allowCoreThreadTimeOut(true);
    }

    private DiagnosticReporter() { }

    static void execute(Runnable task) { worker.execute(task); }

    public static synchronized void report(DiagnosticPoint point, DiagnosticPoint.State state) {
        long now = SystemClock.elapsedRealtime();
        Signal previous = last.get(point);
        if (previous != null && previous.state == state) return;
        Signal next = new Signal(point, state, now);
        last.put(point, next);
        // Coalesce a burst for a single probe while retaining its final state.
        if (previous != null && now - previous.elapsed < MIN_INTERVAL_MS) pending.remove(previous);
        if (pending.size() == MAX_PENDING) pending.removeFirst();
        pending.addLast(next);
        retries = 0;
        schedule(650);
    }

    private static void schedule(long delay) {
        if (!scheduled) {
            scheduled = true;
            worker.schedule(DiagnosticReporter::flush, delay, TimeUnit.MILLISECONDS);
        }
    }

    private static void flush() {
        ArrayList<Signal> batch;
        synchronized (DiagnosticReporter.class) { batch = new ArrayList<>(pending); }
        boolean sent = false;
        long delay = 2_000;
        try {
            Context context = currentContext();
            UserManager users = context == null ? null : context.getSystemService(UserManager.class);
            if (users != null && !users.isUserUnlocked()) {
                delay = 30_000;
                waitForUnlock(context);
            }
            else if (context != null) {
                Bundle request = new Bundle();
                String[] points = new String[batch.size()], states = new String[points.length];
                long[] times = new long[points.length];
                for (int i = 0; i < batch.size(); i++) {
                    Signal signal = batch.get(i);
                    points[i] = signal.point.name(); states[i] = signal.state.name(); times[i] = signal.elapsed;
                }
                request.putStringArray("points", points); request.putStringArray("states", states);
                request.putLongArray("times", times); request.putBinder("process", PROCESS);
                Bundle response = context.getContentResolver().call(DiagnosticProvider.URI, "record",
                        batch.getFirst().point.scope.name(), request);
                sent = response != null;
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            // The reporter must not affect the behavior of an injected scope.
        }
        synchronized (DiagnosticReporter.class) {
            scheduled = false;
            if (sent) { pending.removeAll(batch); retries = 0; }
            if (!pending.isEmpty() && (sent || retries++ < 12)) schedule(sent ? 650 : delay);
        }
    }

    private static synchronized void waitForUnlock(Context context) {
        if (bootUnlock != null) return;
        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override public void onReceive(Context source, Intent intent) {
                context.unregisterReceiver(this);
                synchronized (DiagnosticReporter.class) {
                    bootUnlock = null;
                    retries = 0;
                    schedule(0);
                }
            }
        };
        context.registerReceiver(receiver, new IntentFilter(Intent.ACTION_USER_UNLOCKED), Context.RECEIVER_NOT_EXPORTED);
        bootUnlock = receiver;
    }

    @SuppressLint("PrivateApi") // Early injected hooks have no Application context. Failure drops diagnostics only.
    private static Context currentContext() throws ReflectiveOperationException {
        Class<?> thread = Class.forName("android.app.ActivityThread");
        Context application = (Context) thread.getMethod("currentApplication").invoke(null);
        if (application != null || android.os.Process.myUid() != android.os.Process.SYSTEM_UID) return application;
        Object current = thread.getMethod("currentActivityThread").invoke(null);
        return current == null ? null : (Context) thread.getMethod("getSystemContext").invoke(current);
    }
}
