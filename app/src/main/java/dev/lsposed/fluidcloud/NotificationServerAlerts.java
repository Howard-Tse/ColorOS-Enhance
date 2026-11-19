package dev.lsposed.fluidcloud;

import android.app.KeyguardManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.content.Context;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.VibrationEffect;
import android.service.notification.StatusBarNotification;
import dev.howard.colorosenhance.notifications.NotificationCloudPolicy;
import dev.howard.colorosenhance.notifications.NotificationCloudRules;
import dev.howard.colorosenhance.notifications.NotificationMetadataProvider;
import io.github.libxposed.api.XposedInterface;
import java.lang.reflect.Method;
import java.util.Map;

/** Scope muting to the native sound/vibration getters inside one eligible attention dispatch. */
final class NotificationServerAlerts {
    private final ThreadLocal<Object> quiet = new ThreadLocal<>();
    private final Method sbn, importance, channel;
    private volatile Context context;
    private volatile IBinder renderer;
    private volatile NotificationCloudRules rules = NotificationCloudRules.DEFAULT;
    private volatile boolean enabled;
    private Handler worker;

    NotificationServerAlerts(HookEntry module, ClassLoader loader) throws ReflectiveOperationException {
        String ns = "com.android.server.notification.";
        Class<?> type = Reflect.type(loader, ns + "NotificationAttentionHelper");
        Class<?> record = Reflect.type(loader, ns + "NotificationRecord");
        Class<?> signals = Reflect.type(loader, type.getName() + "$Signals");
        var attention = Reflect.method(type, "buzzBeepBlinkLocked", int.class, record, signals);
        sbn = Reflect.method(record, "getSbn", StatusBarNotification.class);
        importance = Reflect.method(record, "getImportance", int.class);
        channel = Reflect.method(record, "getChannel", NotificationChannel.class);
        var sound = Reflect.method(record, "getSound", Uri.class);
        var vibration = Reflect.method(record, "getVibration", VibrationEffect.class);
        var contextField = Reflect.field(type, "mContext", Context.class);
        // ART can inline the getters into the attention helper.
        if (!module.deoptimize(attention)) throw new IllegalStateException("Unable to deoptimize notification attention");
        var soundHook = module.hook(sound).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain ->
                quiet.get() == chain.getThisObject() ? null : chain.proceed());
        XposedInterface.HookHandle vibrationHook = null;
        try {
            vibrationHook = module.hook(vibration).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain ->
                    quiet.get() == chain.getThisObject() ? null : chain.proceed());
            module.hook(attention).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
                if (!enabled) return chain.proceed();
                if (context == null) rememberContext((Context) Reflect.get(contextField, chain.getThisObject()));
                Object item = chain.getArg(0);
                Object previous = quiet.get();
                if (canMute(item)) quiet.set(item); else quiet.remove();
                try { return chain.proceed(); }
                finally { if (previous == null) quiet.remove(); else quiet.set(previous); }
            });
        } catch (RuntimeException | LinkageError failure) {
            if (vibrationHook != null) vibrationHook.unhook();
            soundHook.unhook();
            throw failure;
        }
        for (var constructor : type.getDeclaredConstructors()) {
            module.hook(constructor).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
                Object result = chain.proceed();
                rememberContext((Context) Reflect.get(contextField, chain.getThisObject()));
                return result;
            });
        }
    }

    void update(Map<String, ?> values) {
        NotificationCloudRules next = NotificationCloudRules.from(values);
        rules = next;
        boolean desired = !next.keepAlerts() && Boolean.TRUE.equals(values.get("enabled"))
                && Boolean.TRUE.equals(values.get(NotificationCloudRules.ENABLED));
        if (enabled == desired) return;
        enabled = desired;
        if (!desired) renderer = null;
        synchronized (this) {
            if (desired) {
                if (worker == null && context != null) startWorker(context);
                else if (worker != null) worker.post(this::readRenderer);
            }
        }
    }

    private synchronized void rememberContext(Context host) {
        if (context != null || host == null) return;
        context = host;
        if (enabled) startWorker(host);
    }
    private void startWorker(Context host) {
        HandlerThread thread = new HandlerThread("coloros-notification-alerts");
        thread.start();
        worker = new Handler(thread.getLooper());
        worker.post(() -> {
            try {
                host.getContentResolver().registerContentObserver(NotificationMetadataProvider.ALERT_STATUS, false,
                        new ContentObserver(worker) {
                            @Override public void onChange(boolean selfChange) { readRenderer(); }
                        });
            } catch (RuntimeException unavailable) { /* Without authorization the original alerts are kept. */ }
            readRenderer();
        });
    }

    private void readRenderer() {
        if (!enabled || context == null) return;
        try {
            var result = context.getContentResolver().call(NotificationMetadataProvider.URI, "read_alert_renderer", null, null);
            IBinder token = result == null ? null : result.getBinder("renderer");
            renderer = token != null && token.isBinderAlive() ? token : null;
        } catch (RuntimeException unavailable) { renderer = null; }
    }

    private boolean canMute(Object item) {
        IBinder state = renderer;
        if (!enabled || context == null || state == null || !state.isBinderAlive()) return false;
        PowerManager power = context.getSystemService(PowerManager.class);
        KeyguardManager lock = context.getSystemService(KeyguardManager.class);
        if (power == null || !power.isInteractive() || lock == null || lock.isKeyguardLocked()) return false;
        StatusBarNotification original = (StatusBarNotification) Reflect.call(sbn, item);
        if (original == null || Notification.CATEGORY_CALL.equals(original.getNotification().category)
                || !rules.accepts(original.getPackageName())) return false;
        NotificationChannel rowChannel = (NotificationChannel) Reflect.call(channel, item);
        int channelImportance = rowChannel == null ? android.app.NotificationManager.IMPORTANCE_UNSPECIFIED : rowChannel.getImportance();
        return NotificationCloudPolicy.evaluate(original, android.os.Process.myUserHandle(),
                (int) Reflect.call(importance, item), channelImportance, false, rules) != NotificationCloudRules.Mode.NATIVE;
    }
}
