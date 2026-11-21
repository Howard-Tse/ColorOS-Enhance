package dev.lsposed.fluidcloud;

import android.service.notification.StatusBarNotification;
import android.view.View;
import dev.howard.colorosenhance.notifications.NotificationCloudStore;
import io.github.libxposed.api.XposedInterface;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/** Let the notification stack own swipe thresholds, menus and the outgoing row animation. */
final class NativeShadeNotificationDismissal {
    private final NotificationCloudFeature feature;
    private final List<XposedInterface.HookHandle> hooks = new ArrayList<>();
    private volatile Map<Object, List<NotificationCloudStore.Entry<StatusBarNotification>>> rows = Map.of();

    NativeShadeNotificationDismissal(NotificationCloudFeature feature, ClassLoader plugin, ClassLoader host)
            throws ReflectiveOperationException {
        this.feature = feature;
        Class<?> entry = Reflect.type(plugin, "com.oplus.systemui.plugins.seedling.notification.data.model.c");
        Class<?> model = Reflect.type(plugin, "com.oplus.systemui.plugins.seedling.data.interactor.m");
        Class<?> core = Reflect.type(plugin, "com.oplus.systemui.plugins.livealert.lib.m");
        var wrapped = Reflect.field(model, "a", core);
        var entryModel = Reflect.uniqueField(entry, field -> Reflect.instance(field) && field.getType() == model);
        var create = entry.getDeclaredConstructor(String.class, View.class, int.class, String.class, String.class,
                boolean.class, boolean.class, String.class, model, int.class, String.class);
        String customCard = "com.oplus.systemui.statusbar.notification.customcard.";
        Class<?> hostRow = Reflect.type(host, customCard + "OplusCustomRow");
        var getEntry = Reflect.method(hostRow, "getEntry", entry.getSuperclass());
        Class<?> manager = Reflect.type(host, customCard + "OplusCustomRowControllerManager");
        var dismiss = Reflect.method(manager, "dissmissCustomRow", void.class, hostRow, String.class);
        try {
            hooks.add(feature.module.hook(create).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
                var currentRows = rows;
                if (currentRows.isEmpty()) return chain.proceed();
                var group = currentRows.get(Reflect.get(wrapped, chain.getArg(8)));
                if (group == null) return chain.proceed();
                boolean clearable = clearable(group);
                if (clearable == (boolean) chain.getArg(6)) return chain.proceed();
                Object[] args = chain.getArgs().toArray();
                args[6] = clearable; // CustomRowEntry.isShowDeleteMenu is also the native swipe eligibility.
                return chain.proceed(args);
            }));
            hooks.add(feature.module.hook(dismiss).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
                var currentRows = rows;
                if (currentRows.isEmpty()) return chain.proceed();
                Object row = Reflect.call(getEntry, chain.getArg(0));
                var group = entry.isInstance(row)
                        ? currentRows.get(Reflect.get(wrapped, Reflect.get(entryModel, row))) : null;
                Object result = chain.proceed();
                // The stock callback clears the representative. A converted grouped row also
                // covers the remaining members, which would otherwise immediately rebuild it.
                if (group != null && group.size() > 1 && clearable(group))
                    feature.main.post(() -> dismissOtherMembers(feature, group));
                return result;
            }));
        } catch (RuntimeException | LinkageError failure) {
            close();
            throw failure;
        }
    }

    private static boolean clearable(List<NotificationCloudStore.Entry<StatusBarNotification>> group) {
        return group.stream().allMatch(member -> member.value().isClearable());
    }

    void publish(Map<String, Object> models, Map<String, List<NotificationCloudStore.Entry<StatusBarNotification>>> coverage) {
        var next = new IdentityHashMap<Object, List<NotificationCloudStore.Entry<StatusBarNotification>>>();
        models.forEach((key, model) -> {
            var group = coverage.get(key);
            if (group != null && !group.isEmpty())
                next.put(model, group);
        });
        rows = next;
    }

    static void dismissOtherMembers(NotificationCloudFeature feature,
            List<NotificationCloudStore.Entry<StatusBarNotification>> group) {
        if (!feature.enabled() || !feature.unlocked() || !feature.rules().shadeCards()) return;
        for (int i = 1; i < group.size(); i++) {
            var source = group.get(i);
            Object current = feature.liveEntry(source.key());
            if (current != null && feature.notification(current) == source.value()) feature.dismissOriginal(source.key());
        }
    }

    void close() {
        rows = Map.of();
        hooks.forEach(XposedInterface.HookHandle::unhook);
        hooks.clear();
    }
}
