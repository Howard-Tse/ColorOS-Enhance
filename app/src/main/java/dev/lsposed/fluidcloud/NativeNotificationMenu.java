package dev.lsposed.fluidcloud;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.Icon;
import io.github.libxposed.api.XposedInterface;
import java.util.ArrayList;
import java.util.List;

/** Feeds original notification commands to ColorOS's own card popup. */
final class NativeNotificationMenu {
    private static final String STOCK_SETTINGS = "oppo.intent.action.NOTIFICATION_CUSTOM_ACTION_SETTING_CLICK";
    private static final String MODULE_SCHEME = "coloros-enhance";

    static void install(NotificationCloudFeature feature, NotificationCloudActions actions, ClassLoader loader)
            throws ReflectiveOperationException {
        String host = "com.android.systemui.plugins.custom.";
        Class<?> entry = Reflect.type(loader, host + "CustomRowEntry");
        Class<?> row = Reflect.type(loader, host + "CustomRowMenuData");
        var item = row.getConstructor(String.class, Icon.class, String.class, PendingIntent.class);
        var key = Reflect.method(entry, "getKey", String.class);
        var rows = Reflect.method(entry, "getPopupMenuList", List.class);
        var setRows = Reflect.method(entry, "setPopupMenuList", void.class, List.class);
        var intent = Reflect.method(row, "getItemClickIntent", PendingIntent.class);
        var request = Reflect.method(PendingIntent.class, "getIntent", Intent.class);
        Class<?> creator = Reflect.type(loader, "com.oplus.systemui.statusbar.notification.customcard.popupmenu.OplusCustomPopupMenuItemCreatorImpl");
        var build = Reflect.method(creator, "createMenuList", List.class, Context.class, entry);
        feature.module.hook(build).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
            if (!feature.enabled() || !feature.unlocked()) return chain.proceed();
            Object model = chain.getArg(1);
            String selected = (String) Reflect.call(key, model);
            if (!feature.store.ready(selected)) return chain.proceed();
            List<?> previous = (List<?>) Reflect.call(rows, model);
            List<Object> next = new ArrayList<>();
            if (previous != null) for (Object existing : previous) {
                var launcher = (PendingIntent) Reflect.call(intent, existing);
                if (launcher == null) continue;
                var target = (Intent) Reflect.call(request, launcher);
                if (target == null || STOCK_SETTINGS.equals(target.getAction()) || MODULE_SCHEME.equals(target.getScheme())) continue;
                next.add(existing);
            }
            for (var command : actions.nativeMenu(selected))
                next.add(item.newInstance(selected, null, command.label(), actions.menuIntent(selected, command)));
            if (!next.isEmpty()) Reflect.call(setRows, model, next);
            return chain.proceed();
        });
    }

    private NativeNotificationMenu() { }
}
