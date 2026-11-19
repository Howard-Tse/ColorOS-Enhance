package dev.howard.colorosenhance.diagnostics;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Process;
import android.os.RemoteException;
import android.os.SystemClock;
import dev.howard.colorosenhance.BuildConfig;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.Map;

/** Write-only ingress for the module's injected, preinstalled host processes. */
public final class DiagnosticProvider extends ContentProvider {
    static final Uri URI = Uri.parse("content://" + BuildConfig.PUBLIC_APPLICATION_ID + ".diagnostics");
    private static final Map<DiagnosticPoint, IBinder> processes = new EnumMap<>(DiagnosticPoint.class);

    @Override public boolean onCreate() { return true; }

    @Override public Bundle call(String method, String arg, Bundle extras) {
        if (!"record".equals(method)) throw new UnsupportedOperationException();
        int uid = Binder.getCallingUid();
        Context context = getContext();
        long identity = Binder.clearCallingIdentity();
        try {
            if (context == null || extras == null) return null;
            DiagnosticPoint.Scope scope = DiagnosticPoint.Scope.valueOf(arg);
            if (!isHost(context, scope, uid)) throw new SecurityException("Diagnostic host denied");
            String[] points = extras.getStringArray("points"), states = extras.getStringArray("states");
            long[] times = extras.getLongArray("times");
            IBinder process = extras.getBinder("process");
            if (process == null || !process.isBinderAlive() || points == null || states == null || times == null
                    || points.length == 0 || points.length > 96 || states.length != points.length || times.length != points.length) return null;
            long now = System.currentTimeMillis(), elapsed = SystemClock.elapsedRealtime();
            ArrayList<DiagnosticStore.Signal> signals = new ArrayList<>();
            for (int i = 0; i < points.length; i++) {
                DiagnosticPoint point = DiagnosticPoint.valueOf(points[i]);
                if (scope != point.scope || times[i] < 0 || times[i] > elapsed) return null;
                signals.add(new DiagnosticStore.Signal(point, DiagnosticPoint.State.valueOf(states[i]), now - (elapsed - times[i])));
            }
            DiagnosticStore.get(context).record(signals, DiagnosticStore.boot(context), now);
            synchronized (processes) {
                try {
                    if (!processes.containsValue(process)) process.linkToDeath(() -> {
                        synchronized (processes) { processes.values().removeIf(process::equals); }
                    }, 0);
                    for (var signal : signals) processes.put(signal.point(), process);
                } catch (RemoteException exited) { /* Keep its history without marking it live. */ }
            }
            return Bundle.EMPTY;
        } catch (IllegalArgumentException | NullPointerException malformed) {
            return null;
        } finally { Binder.restoreCallingIdentity(identity); }
    }

    private static boolean isHost(Context context, DiagnosticPoint.Scope scope, int uid) {
        if (scope == DiagnosticPoint.Scope.SYSTEM) return uid == Process.SYSTEM_UID;
        try {
            ApplicationInfo app = context.getPackageManager().getApplicationInfo(scope.packageName, 0);
            return app.uid == uid && (app.flags & (ApplicationInfo.FLAG_SYSTEM | ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0;
        } catch (PackageManager.NameNotFoundException missing) { return false; }
    }

    static boolean live(DiagnosticPoint.Scope scope) {
        synchronized (processes) {
            for (var entry : processes.entrySet()) {
                if (entry.getKey().scope == scope && entry.getValue().isBinderAlive()) return true;
            }
            return false;
        }
    }

    static boolean live(DiagnosticPoint point) {
        synchronized (processes) {
            IBinder process = processes.get(point);
            return process != null && process.isBinderAlive();
        }
    }

    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String order) { return null; }
    @Override public String getType(Uri uri) { return null; }
    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] args) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri uri, String selection, String[] args) { throw new UnsupportedOperationException(); }
}
