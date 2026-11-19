package dev.lsposed.fluidcloud;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.UserHandle;
import android.service.notification.StatusBarNotification;
import android.view.Display;
import java.lang.reflect.Method;

/** ColorOS notification zoom uses OEM launch options on the original PendingIntent. */
final class NativeNotificationFloating {
    private final Context context;
    private final Method getManager, supportsZoom, getIntent, userId, activityOptions, send;

    NativeNotificationFloating(Context context) throws ReflectiveOperationException {
        this.context = context;
        ClassLoader loader = context.getClassLoader();
        Class<?> manager = Reflect.type(loader, "com.oplus.zoomwindow.OplusZoomWindowManager");
        getManager = Reflect.method(manager, "getInstance", manager);
        supportsZoom = Reflect.method(manager, "isSupportZoomMode", boolean.class, String.class, int.class,
                String.class, Bundle.class);
        getIntent = Reflect.method(PendingIntent.class, "getIntent", Intent.class);
        userId = Reflect.method(UserHandle.class, "getIdentifier", int.class);
        Class<?> starter = Reflect.type(loader, "com.android.systemui.statusbar.phone.ActivityStarterUtilsKt");
        Class<?> animation = Reflect.type(loader, "android.view.RemoteAnimationAdapter");
        activityOptions = Reflect.method(starter, "createActivityOptions", Bundle.class, int.class, animation);
        send = Reflect.method(PendingIntent.class, "sendAndReturnResult", int.class, Context.class, int.class,
                Intent.class, PendingIntent.OnFinished.class, Handler.class, String.class, Bundle.class);
    }

    boolean supports(StatusBarNotification notification) {
        PendingIntent target = notification.getNotification().contentIntent;
        if (target == null || !target.isActivity()) return false;
        UserHandle creator = target.getCreatorUserHandle();
        if (creator == null) return false;
        Intent destination = (Intent) Reflect.call(getIntent, target);
        if (destination == null) return false;
        var component = destination.getComponent();
        String pkg = component == null ? destination.getPackage() : component.getPackageName();
        if (pkg == null) pkg = target.getCreatorPackage();
        int user = (int) Reflect.call(userId, creator);
        Object manager = Reflect.call(getManager, null);
        return pkg != null && supports(manager, pkg, user)
                && (component == null || supports(manager, component.flattenToShortString(), user));
    }

    private boolean supports(Object manager, String target, int user) {
        return Boolean.TRUE.equals(Reflect.call(supportsZoom, manager, target, user, context.getPackageName(), null));
    }

    boolean open(StatusBarNotification notification) {
        Bundle options = (Bundle) Reflect.call(activityOptions, null, Display.DEFAULT_DISPLAY, null);
        // These are the options sent by the native notification banner's zoom launch.
        options.putInt("extra_window_mode", 100);
        options.putInt("android:activity.mZoomLaunchFlags", 5);
        options.putInt("androidx.activity.LaunchScenario", 1);
        options.putString("launch_source_type", "android".equals(notification.getOpPkg()) ? "opushnotification" : "localnotification");
        // Sending the PendingIntent preserves its creator and existing authorization.
        return (int) Reflect.call(send, notification.getNotification().contentIntent, context, 0, null, null, null, null, options) >= 0;
    }
}
