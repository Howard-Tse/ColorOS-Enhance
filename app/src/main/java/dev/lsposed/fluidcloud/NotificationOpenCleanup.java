package dev.lsposed.fluidcloud;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.service.notification.StatusBarNotification;
import dev.howard.colorosenhance.notifications.NotificationCloudRules.AfterOpen;
import io.github.libxposed.api.XposedInterface;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.concurrent.ConcurrentHashMap;

/** Observe the actual dispatch result rather than removing content when a launch is merely queued. */
final class NotificationOpenCleanup {
    private record Open(String key, StatusBarNotification source, AfterOpen mode) { }
    private final NotificationCloudFeature feature;
    private final ConcurrentHashMap<PendingIntent, Open> pending = new ConcurrentHashMap<>();
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
                Object result = chain.proceed();
                if (request != null && result instanceof Integer code && code >= 0)
                    feature.main.postDelayed(() -> complete(request), 360);
                return result;
            });
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
            feature.failure("Notification launch completion observer unavailable", failure);
        }
    }

    void arm(String key, PendingIntent target) {
        if (target == null || !feature.enabled() || !feature.unlocked() || feature.rules().afterOpen() == AfterOpen.KEEP) return;
        var entry = feature.store.get(key);
        if (entry == null) return;
        Open request = new Open(key, entry.value(), feature.rules().afterOpen());
        pending.put(target, request);
        feature.main.postDelayed(() -> pending.remove(target, request), 2500);
    }

    private void complete(Open open) {
        if (!feature.enabled() || !feature.unlocked() || feature.rules().afterOpen() != open.mode()) return;
        Object item = feature.liveEntry(open.key());
        if (item == null || feature.notification(item) != open.source()) return;
        applyAfterOpen(open.mode(), () -> feature.dismissOriginal(open.key()), () -> feature.returnOriginal(open.key()));
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
