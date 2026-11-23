package dev.lsposed.fluidcloud;

import io.github.libxposed.api.XposedInterface;
import java.lang.reflect.Method;
import java.util.function.BooleanSupplier;

/** Uses ColorOS's persistent-entry age only inside our lockscreen list eligibility query. */
final class NotificationCardLifetime {
    private final ThreadLocal<Object> checkedExtension = new ThreadLocal<>();
    private final Method extension;

    NotificationCardLifetime(Method extension) { this.extension = extension; }

    static NotificationCardLifetime install(NotificationCloudFeature feature, Class<?> entry,
            Method visibility) throws ReflectiveOperationException {
        ClassLoader loader = entry.getClassLoader();
        Class<?> api = Reflect.type(loader, "com.android.systemui.statusbar.notification.collection.OplusNotificationEntryEx");
        Class<?> impl = Reflect.type(loader, "com.oplus.systemui.statusbar.notification.collection.OplusNotificationEntryExImpl");
        Class<?> policy = Reflect.type(loader, "com.oplus.systemui.statusbar.notification.interruption.OplusKeyguardNotificationVisibilityProviderExImpl");
        Method extension = Reflect.method(entry, "getEx", api);
        Method timestamp = Reflect.method(impl, "getPostTime", long.class);
        Method ageCaller = Reflect.method(policy, "shouldHideNotification", boolean.class, entry);
        var lifetime = new NotificationCardLifetime(extension);
        // getPostTime can be inlined into the OEM policy, which itself is called by SystemUI.
        if (!feature.module.deoptimize(ageCaller) || !feature.module.deoptimize(visibility))
            throw new IllegalStateException("Lockscreen notification age check unavailable");
        feature.module.hook(timestamp).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
            if (lifetime.checking(chain.getThisObject())) return Long.MAX_VALUE;
            return chain.proceed();
        });
        return lifetime;
    }

    boolean checking(Object owner) { return owner != null && checkedExtension.get() == owner; }

    boolean evaluate(Object entry, BooleanSupplier nativePolicy) {
        Object previous = checkedExtension.get();
        checkedExtension.set(Reflect.call(extension, entry));
        try { return nativePolicy.getAsBoolean(); }
        finally {
            if (previous == null) checkedExtension.remove();
            else checkedExtension.set(previous);
        }
    }
}
