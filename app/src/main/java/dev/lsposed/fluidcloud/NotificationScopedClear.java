package dev.lsposed.fluidcloud;

import android.app.Notification;
import android.service.notification.StatusBarNotification;
import io.github.libxposed.api.XposedInterface;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.WeakHashMap;

/** Keep the native clear animation, then dismiss only entries from the page where it started. */
final class NotificationScopedClear {
    private record Scope(Set<String> allowed, Set<String> collected, boolean collection,
                         java.util.Map<String, Object> groupedChildren) { }
    private final NotificationCloudFeature feature;
    private final WeakHashMap<Object, Scope> pending = new WeakHashMap<>();
    private Scope selecting;
    private Set<String> dismissing;

    NotificationScopedClear(NotificationCloudFeature feature, ClassLoader loader, Class<?> stack, Class<?> row,
                            Method rowEntry) throws ReflectiveOperationException {
        this.feature = feature;
        Method clear = Reflect.method(stack, "clearNotifications", void.class, int.class, boolean.class, boolean.class);
        Method include = Reflect.method(stack, "includeChildInClearAll", boolean.class, row, int.class);
        Method complete = Reflect.method(stack, "onClearAllAnimationsEnd", void.class, int.class, List.class);
        var inProgress = Reflect.field(stack, "mClearAllInProgress", boolean.class);
        Class<?> dismissEntry = Reflect.type(loader,
                "com.android.systemui.statusbar.notification.collection.EntryWithDismissStats");
        Method key = Reflect.method(dismissEntry, "getKey", String.class);
        Class<?> base = Reflect.type(loader, "com.oplus.systemui.notification.data.NotificationForceGroupEx");
        var expanders = new java.util.LinkedHashSet<Method>();
        expanders.add(Reflect.method(base, "includeOplusAutoGroupToDismiss", List.class,
                List.class, java.util.Collection.class, java.util.Map.class));
        try {
            Class<?> oplus = Reflect.type(loader, "com.oplus.systemui.notification.data.forcegroup.OplusNotificationForceGroupExImpl");
            expanders.add(Reflect.method(oplus, "includeOplusAutoGroupToDismiss", List.class,
                    List.class, java.util.Collection.class, java.util.Map.class));
        } catch (ClassNotFoundException older) { /* The base extension handles this firmware. */ }

        for (Method expand : expanders) feature.module.hook(expand).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
            Object result = chain.proceed();
            if (dismissing == null) return result;
            List<?> entries = (List<?>) result;
            return entries.stream().filter(entry -> dismissing.contains(Reflect.call(key, entry))).toList();
        });
        feature.module.hook(include).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
            Object result = chain.proceed();
            if (selecting == null || !Boolean.TRUE.equals(result)) return result;
            Object entry = Reflect.call(rowEntry, chain.getArg(0));
            if (entry == null) return false;
            StatusBarNotification item = feature.notification(entry);
            // The native traversal uses its summary as the gate to reach attached children.
            return isSummary(item) || selecting.allowed().contains(item.getKey());
        });
        feature.module.hook(complete).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
            Scope scope = pending.remove(chain.getThisObject());
            if (scope == null) scope = scope();
            if (scope == null) return chain.proceed();
            Set<String> partition = new HashSet<>(scope.collected());
            if (!scope.collection()) partition.addAll(feature.collectedKeys());
            Set<String> allowed = allowedKeys(feature.liveNotifications(), partition, scope.collection());
            allowed.retainAll(scope.allowed());
            var rows = new ArrayList<>();
            Set<String> included = new HashSet<>();
            for (Object view : (List<?>) chain.getArg(1)) {
                if (!row.isInstance(view)) continue;
                Object entry = Reflect.call(rowEntry, view);
                if (entry == null) continue;
                String notificationKey = feature.notification(entry).getKey();
                if (allowed.contains(notificationKey) && feature.liveEntry(notificationKey) == entry
                        && included.add(notificationKey)) rows.add(view);
            }
            // The stock zero selection calls the system-wide clear API. Nonzero uses this list.
            int selection = (int) chain.getArg(0);
            dismissing = allowed;
            try {
                Object result = chain.proceed(new Object[]{selection == 0 ? 1 : selection, rows});
                for (var child : scope.groupedChildren().entrySet()) {
                    String notificationKey = child.getKey();
                    if (allowed.contains(notificationKey) && !included.contains(notificationKey)
                            && feature.liveEntry(notificationKey) == child.getValue()) feature.dismissOriginal(notificationKey);
                }
                return result;
            }
            finally { dismissing = null; }
        });
        feature.module.hook(clear).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
            Object owner = chain.getThisObject();
            if (pending.containsKey(owner) && Boolean.TRUE.equals(Reflect.get(inProgress, owner))) return null;
            Scope scope = scope();
            pending.remove(owner);
            if (scope == null) return chain.proceed();
            if (Integer.valueOf(0).equals(chain.getArg(0))) {
                // A collapsed group with a protected summary can skip its clearable children
                // entirely in the stock animation traversal. Check those individual rows too.
                var children = new java.util.LinkedHashMap<String, Object>();
                for (String notificationKey : scope.allowed()) {
                    Object entry = feature.liveEntry(notificationKey);
                    if (entry == null) continue;
                    StatusBarNotification item = feature.notification(entry);
                    if (item.isGroup() && !isSummary(item)) children.put(notificationKey, entry);
                }
                scope = new Scope(scope.allowed(), scope.collected(), scope.collection(), children);
            }
            pending.put(owner, scope);
            selecting = scope;
            try {
                return scope.collection() ? chain.proceed(new Object[]{chain.getArg(0), false, chain.getArg(2)})
                        : chain.proceed();
            } finally {
                selecting = null;
            }
        });
    }

    private Scope scope() {
        boolean collection = feature.collectionPageShowing();
        Set<String> collected = Set.copyOf(feature.collectedKeys());
        return !collection && collected.isEmpty() ? null :
                new Scope(allowedKeys(feature.liveNotifications(), collected, collection), collected, collection, java.util.Map.of());
    }

    static Set<String> allowedKeys(List<StatusBarNotification> notifications, Set<String> collected, boolean collection) {
        Set<String> targetGroups = new HashSet<>(), excludedGroups = new HashSet<>(), keys = new HashSet<>();
        for (StatusBarNotification item : notifications) {
            if (isSummary(item) || NotificationCloudPresentation.summary(item)) continue;
            boolean inScope = collected.contains(item.getKey()) == collection;
            if (item.isGroup()) (inScope && clearable(item) ? targetGroups : excludedGroups).add(item.getGroupKey());
            if (inScope && clearable(item)) keys.add(item.getKey());
        }
        for (StatusBarNotification item : notifications) {
            if (isSummary(item) && !NotificationCloudPresentation.summary(item) && clearable(item)
                    && !excludedGroups.contains(item.getGroupKey())
                    && (!collection || targetGroups.contains(item.getGroupKey()))) keys.add(item.getKey());
        }
        return keys;
    }

    private static boolean clearable(StatusBarNotification item) {
        return item.isClearable() && (item.getNotification().flags & Notification.FLAG_BUBBLE) == 0;
    }

    private static boolean isSummary(StatusBarNotification item) {
        return (item.getNotification().flags & Notification.FLAG_GROUP_SUMMARY) != 0;
    }
}
