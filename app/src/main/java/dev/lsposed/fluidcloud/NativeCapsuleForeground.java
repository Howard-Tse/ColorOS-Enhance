package dev.lsposed.fluidcloud;

import io.github.libxposed.api.XposedInterface;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.function.BooleanSupplier;

/** Controls the OEM front-app interceptor only for the status-bar entry. */
final class NativeCapsuleForeground {
    static final String KEY = "capsule_keep_in_foreground";
    private final Field repository;
    private final Method refreshEntry;
    private final Object statusBar;
    private final BooleanSupplier enabled;
    private boolean applied;

    NativeCapsuleForeground(HookEntry module, ClassLoader loader, BooleanSupplier enabled,
                            java.util.List<XposedInterface.HookHandle> hooks) throws ReflectiveOperationException {
        this.enabled = enabled;
        String prefix = "com.oplus.systemui.plugins.";
        Class<?> model = Reflect.type(loader, prefix + "seedling.data.interactor.m");
        Class<?> interceptor = Reflect.type(loader, prefix + "seedling.data.interceptor.b");
        Method filter = Reflect.uniqueMethod(interceptor, method -> method.getName().equals("a")
                && method.getReturnType() == boolean.class && method.getParameterCount() == 2
                && method.getParameterTypes()[0] == model);
        Class<?> entryType = Reflect.type(loader, prefix + "livealert.lib.EntryType");
        // R8 renames the enum field; its serialized enum name stays stable.
        statusBar = java.util.Arrays.stream(entryType.getEnumConstants())
                .filter(value -> value instanceof Enum<?> item && item.name().equals("ENTRY_STATUS_BAR"))
                .findFirst().orElseThrow(() -> new NoSuchFieldException("Status-bar entry"));
        Method entry = Reflect.method(model, "k", entryType);
        Field kind = Reflect.field(interceptor, "a", int.class);
        Field suppressed = Reflect.field(model, "e", boolean.class);
        Method proceed = Reflect.method(filter.getParameterTypes()[1], "g", boolean.class, model);
        Class<?> repositoryType = Reflect.type(loader, prefix + "seedling.data.interactor.l");
        repository = Reflect.uniqueField(repositoryType,
                field -> !Reflect.instance(field) && field.getType() == repositoryType);
        refreshEntry = Reflect.method(repositoryType, "c", void.class, entryType, boolean.class);
        hooks.add(module.hook(filter).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
            Object item = chain.getArg(0);
            // This synthetic class also implements deep-brick and super-power interceptors.
            if (!enabled.getAsBoolean() || kind.getInt(chain.getThisObject()) != 0
                    || Reflect.call(entry, item) != statusBar) return chain.proceed();
            suppressed.setBoolean(item, false);
            return Reflect.call(proceed, chain.getArg(1), item);
        }));
    }

    void refresh() {
        boolean next = enabled.getAsBoolean();
        if (next == applied) return;
        Object source = Reflect.get(repository, null);
        if (source != null) {
            Reflect.call(refreshEntry, source, statusBar, false);
            applied = next;
        }
    }
}
