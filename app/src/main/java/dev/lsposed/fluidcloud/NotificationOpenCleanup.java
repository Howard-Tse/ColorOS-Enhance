package dev.lsposed.fluidcloud;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.SystemClock;
import android.service.notification.StatusBarNotification;
import dev.howard.colorosenhance.notifications.NotificationCloudRules.AfterOpen;
import io.github.libxposed.api.XposedInterface;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.concurrent.ConcurrentHashMap;

/** Observe the actual dispatch result rather than removing content when a launch is merely queued. */
final class NotificationOpenCleanup {
    private static final long DISPATCH_TIMEOUT_MS = 2500L;
    private static final long OPEN_CANCEL_WINDOW_MS = 5000L;
    private record Open(StatusBarNotification source, AfterOpen mode, boolean trackRemoval) { }
    private record SentOpen(StatusBarNotification source, long expiresAt) { }
    private final NotificationCloudFeature feature;
    private final ConcurrentHashMap<PendingIntent, Open> pending = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, SentOpen> sent = new ConcurrentHashMap<>();
    private Method dismiss, visibility, clearable;
    private Constructor<?> stats;

    NotificationOpenCleanup(NotificationCloudFeature feature, ClassLoader loader, Class<?> collection, Class<?> entry) {
        this.feature = feature;
        try {
            Class<?> visibilityType = Reflect.type(loader, "com.android.internal.statusbar.NotificationVisibility");
            Class<?> statsType = Reflect.type(loader, "com.android.systemui.statusbar.notification.collection.notifcollection.DismissedByUserStats");
            visibility = Reflect.method(visibilityType, "obtain", visibilityType, String.class, int.class, int.class, boolean.class);
            try { stats = statsType.getDeclaredConstructor(int.class, visibilityType); }
            catch (NoSuchMethodException older) { stats = statsType.getDeclaredConstructor(int.class, int.class, visibilityType); }
            stats.setAccessible(true);
            clearable = Reflect.method(entry, "isClearable", boolean.class);
            dismiss = Reflect.method(collection, "dismissNotification", void.class, entry, statsType);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
            feature.failure("Native original notification dismissal unavailable", failure);
        }
        try {
            Method send = Reflect.method(PendingIntent.class, "sendAndReturnResult", int.class, Context.class, int.class,
                    Intent.class, PendingIntent.OnFinished.class, Handler.class, String.class, Bundle.class);
            feature.module.hook(send).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
                PendingIntent target = (PendingIntent) chain.getThisObject();
                Open request = pending.isEmpty() ? null : pending.remove(target);
                if (request == null) return chain.proceed();
                String key = request.source().getKey();
                // The target can cancel its notification before dispatch returns.
                SentOpen launch = request.trackRemoval()
                        ? new SentOpen(request.source(), SystemClock.elapsedRealtime() + OPEN_CANCEL_WINDOW_MS) : null;
                if (launch != null) sent.put(key, launch);
                boolean dispatched = false;
                try {
                    Object result = chain.proceed();
                    dispatched = result instanceof Integer code && code >= 0;
                    if (dispatched && request.mode() != AfterOpen.KEEP)
                        feature.main.postDelayed(() -> complete(request), 360);
                    return result;
                } finally {
                    if (launch != null) {
                        if (dispatched) feature.main.postDelayed(() -> sent.remove(key, launch), OPEN_CANCEL_WINDOW_MS);
                        else sent.remove(key, launch);
                    }
                }
            });
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
            feature.failure("Notification launch completion observer unavailable", failure);
            return;
        }
        try {
            Class<?> starter = Reflect.type(loader, "com.android.systemui.statusbar.phone.StatusBarNotificationActivityStarter");
            Class<?> row = Reflect.type(loader, "com.android.systemui.statusbar.notification.row.ExpandableNotificationRow");
            Method start = Reflect.method(starter, "startNotificationIntent", void.class,
                    PendingIntent.class, Intent.class, entry, row, boolean.class, boolean.class, boolean.class);
            feature.module.hook(start).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
                if (feature.enabled() && feature.rules().history())
                    arm(feature.notification(chain.getArg(2)), (PendingIntent) chain.getArg(0), AfterOpen.KEEP, true);
                return chain.proceed();
            });
        } catch (ReflectiveOperationException | RuntimeException | LinkageError unavailable) {
            feature.failure("Native notification launch observer unavailable", unavailable);
        }
    }

    void arm(String key, PendingIntent target) {
        if (target == null || !feature.enabled()) return;
        AfterOpen mode = feature.unlocked() ? feature.rules().afterOpen() : AfterOpen.KEEP;
        boolean trackRemoval = feature.rules().history();
        if (!trackRemoval && mode == AfterOpen.KEEP) return;
        var entry = feature.store.get(key);
        if (entry != null) arm(entry.value(), target, mode, trackRemoval);
    }

    private void arm(StatusBarNotification source, PendingIntent target, AfterOpen mode, boolean trackRemoval) {
        if (target == null) return;
        Open request = new Open(source, mode, trackRemoval);
        pending.merge(target, request, (queued, next) -> queued.source() == source
                && queued.mode() != AfterOpen.KEEP && mode == AfterOpen.KEEP ? queued : next);
        feature.main.postDelayed(() -> pending.remove(target, request), DISPATCH_TIMEOUT_MS);
    }

    boolean consumeOpen(StatusBarNotification source, long now) {
        String key = source.getKey();
        SentOpen launch = sent.get(key);
        return launch != null && launch.source() == source && sent.remove(key, launch) && now <= launch.expiresAt();
    }

    private void complete(Open open) {
        if (!feature.enabled() || !feature.unlocked() || feature.rules().afterOpen() != open.mode()) return;
        String key = open.source().getKey();
        Object item = feature.liveEntry(key);
        if (item == null || feature.notification(item) != open.source()) return;
        applyAfterOpen(open.mode(), () -> feature.dismissOriginal(key), () -> feature.returnOriginal(key));
    }

    static void applyAfterOpen(AfterOpen mode, Runnable dismissOriginal, Runnable returnOriginal) {
        // A failed attempt to clear an ongoing notification must not turn into "clear card only".
        switch (mode) {
            case BOTH -> dismissOriginal.run();
            case CARD -> returnOriginal.run();
            case KEEP -> { }
        }
    }

    boolean dismiss(Object collection, Object entry) {
        if (dismiss == null || collection == null || entry == null) return false;
        StatusBarNotification sbn = feature.notification(entry);
        if (!sbn.isClearable() || !Boolean.TRUE.equals(Reflect.call(clearable, entry))) return false;
        try {
            Object view = Reflect.call(visibility, null, sbn.getKey(), -1, 0, false);
            Object reason = stats.getParameterCount() == 2 ? stats.newInstance(1, view) : stats.newInstance(1, 0, view);
            Reflect.call(dismiss, collection, entry, reason);
            return true;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
            feature.failure("Original notification dismissal unavailable", failure);
            return false;
        }
    }
}
