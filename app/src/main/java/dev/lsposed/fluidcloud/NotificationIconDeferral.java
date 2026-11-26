package dev.lsposed.fluidcloud;

import android.app.Notification;
import android.service.notification.StatusBarNotification;
import dev.howard.colorosenhance.notifications.NotificationCloudRules;
import io.github.libxposed.api.XposedInterface;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.function.Predicate;

/** Defer only status-bar icons while a new notification's replacement is being prepared. */
final class NotificationIconDeferral {
    private static final long CONVERSION_TIMEOUT_MS = 850;
    private final NotificationCloudFeature feature;
    private final Predicate<String> replaced;
    private final Object filterState;
    private final Method setValue;
    private final Method iconKey;
    private final Set<String> attempted = new HashSet<>();
    private final Map<String, Runnable> pending = new HashMap<>();
    private Set<String> masked = Set.of();
    private final Set<String> restoring = new HashSet<>();
    private final Runnable endRestore = this::finishUnlock;

    NotificationIconDeferral(NotificationCloudFeature feature, ClassLoader loader, Predicate<String> replaced)
            throws ReflectiveOperationException {
        this.feature = feature;
        this.replaced = replaced;
        String iconsPackage = "com.android.systemui.statusbar.notification.icon.domain.interactor.";
        Class<?> source = Reflect.type(loader, iconsPackage + "StatusBarNotificationIconsInteractor");
        Class<?> model = Reflect.type(loader, iconsPackage + "ActiveNotificationIconModel");
        Method key;
        try { key = Reflect.method(model, "getNotifKey", String.class); }
        catch (NoSuchMethodException olderFirmware) { key = Reflect.method(model, "getKey", String.class); }
        iconKey = key;
        Class<?> flow = Reflect.type(loader, "kotlinx.coroutines.flow.Flow");
        Class<?> mutable = Reflect.type(loader, "kotlinx.coroutines.flow.MutableStateFlow");
        Class<?> stateFlow = Reflect.type(loader, "kotlinx.coroutines.flow.StateFlowKt");
        Class<?> combineFlows = Reflect.type(loader, "kotlinx.coroutines.flow.FlowKt");
        Class<?> function = Reflect.type(loader, "kotlin.jvm.functions.Function3");
        Method combine = Reflect.method(combineFlows, "combine", flow, flow, flow, function);
        setValue = Reflect.method(mutable, "setValue", void.class, Object.class);
        filterState = Reflect.call(Reflect.method(stateFlow, "MutableStateFlow", mutable, Object.class), null, masked);
        Method getIcons = Reflect.method(source, "getStatusBarNotifs", flow);
        Method getHasIcons = Reflect.method(source, "getHasStatusBarNotifications", flow);
        var flows = new WeakHashMap<Object, Object>();
        var presenceFlows = new WeakHashMap<Object, Object>();
        Object filter = flowTransform(function, (icons, mask) -> filterIcons(icons, mask));
        Object hasIcons = flowTransform(function, (icons, ignored) -> icons instanceof Set<?> set && !set.isEmpty());
        feature.module.hook(getIcons).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
            Object nativeFlow = chain.proceed();
            synchronized (flows) {
                return flows.computeIfAbsent(chain.getThisObject(),
                        ignored -> Reflect.call(combine, null, nativeFlow, filterState, filter));
            }
        });
        feature.module.hook(getHasIcons).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
            Object object = chain.getThisObject();
            synchronized (presenceFlows) {
                return presenceFlows.computeIfAbsent(object,
                        ignored -> Reflect.call(combine, null, Reflect.call(getIcons, object), filterState, hasIcons));
            }
        });
    }

    private interface Transform { Object apply(Object values, Object mask); }

    private static Object flowTransform(Class<?> function, Transform transform) {
        return Proxy.newProxyInstance(function.getClassLoader(), new Class<?>[]{function}, (proxy, method, args) ->
                switch (method.getName()) {
                    case "invoke" -> transform.apply(args[0], args[1]);
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    case "toString" -> "Notification icon deferral";
                    default -> null;
                });
    }

    private Object filterIcons(Object icons, Object mask) {
        if (!(icons instanceof Set<?> set) || !(mask instanceof Set<?> hidden) || hidden.isEmpty()) return icons;
        LinkedHashSet<?> visible = null;
        for (Object icon : set) {
            if (!iconKey.getDeclaringClass().isInstance(icon) || !hidden.contains(Reflect.call(iconKey, icon))) continue;
            if (visible == null) visible = new LinkedHashSet<>(set);
            visible.remove(icon);
        }
        return visible == null ? icons : visible;
    }

    void beforeShadeRender(StatusBarNotification notification, Object entry) {
        String key = notification.getKey();
        if (NotificationCloudPresentation.summary(notification)) {
            if (!masked.contains(key)) reconcile();
            return;
        }
        if (!feature.rules().hideOriginal() || attempted.contains(key) || replaced.test(key)
                || feature.rules().original() == NotificationCloudRules.Original.COLLECT
                        && !feature.collectionSupported()
                || !feature.mayReplaceBanner(entry, notification)) return;
        attempted.add(key);
        Runnable timeout = () -> {
            pending.remove(key);
            reconcile();
        };
        pending.put(key, timeout);
        feature.main.postDelayed(timeout, CONVERSION_TIMEOUT_MS);
        reconcile();
    }

    void reconcile() {
        reconcile(feature.liveNotifications());
    }

    void startUnlock() {
        // Prime the icon StateFlow before SystemUI announces that the shade is unlocked.
        // No private row or capsule is rendered until the normal unlock check succeeds.
        if (!restoring.isEmpty()) return;
        var live = feature.liveNotifications();
        for (var item : live) {
            Object entry = feature.liveEntry(item.getKey());
            if (entry != null && feature.canConvertForIcon(entry, item)) restoring.add(item.getKey());
        }
        if (restoring.isEmpty()) return;
        feature.main.postDelayed(endRestore, CONVERSION_TIMEOUT_MS);
        reconcile(live);
    }

    void finishUnlock() {
        feature.main.removeCallbacks(endRestore);
        if (restoring.isEmpty()) return;
        restoring.clear();
        reconcile();
    }

    void removed(String key) {
        restoring.remove(key);
        attempted.remove(key);
        Runnable timeout = pending.remove(key);
        if (timeout != null) feature.main.removeCallbacks(timeout);
    }

    void reconcile(List<StatusBarNotification> notifications) {
        Set<String> next = new HashSet<>(), live = new HashSet<>();
        Set<String> heldGroups = new HashSet<>(), ordinaryGroups = new HashSet<>();
        boolean enabled = feature.enabled() && (feature.unlocked() || feature.rules().retainOnLock())
                && feature.rules().hideOriginal();
        for (StatusBarNotification notification : notifications) {
            String key = notification.getKey();
            live.add(key);
            if (NotificationCloudPresentation.summary(notification)) {
                next.add(key);
                continue;
            }
            if (!enabled && restoring.isEmpty()) continue;
            boolean confirmed = enabled && replaced.test(key);
            if (confirmed) restoring.remove(key);
            boolean hidden = confirmed || enabled && pending.containsKey(key)
                    || feature.enabled() && feature.rules().hideOriginal() && restoring.contains(key);
            if (hidden) next.add(key);
            if (notification.isGroup() && !isSummary(notification)) {
                (hidden ? heldGroups : ordinaryGroups).add(notification.getGroupKey());
            }
        }
        heldGroups.removeAll(ordinaryGroups);
        if (!heldGroups.isEmpty()) for (StatusBarNotification notification : notifications) {
            if (isSummary(notification) && heldGroups.contains(notification.getGroupKey())) next.add(notification.getKey());
        }
        attempted.retainAll(live);
        restoring.retainAll(live);
        if (restoring.isEmpty()) feature.main.removeCallbacks(endRestore);
        pending.entrySet().removeIf(entry -> {
            if (enabled && live.contains(entry.getKey()) && !replaced.test(entry.getKey())) return false;
            feature.main.removeCallbacks(entry.getValue());
            return true;
        });
        if (next.equals(masked)) return;
        masked = Set.copyOf(next);
        Reflect.call(setValue, filterState, masked);
    }

    private static boolean isSummary(StatusBarNotification notification) {
        return (notification.getNotification().flags & Notification.FLAG_GROUP_SUMMARY) != 0;
    }
}
