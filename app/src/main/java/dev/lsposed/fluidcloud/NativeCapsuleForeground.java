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
        boolean alternate = ColorOs16Plugin.capsule(Reflect.type(loader, NativeCapsuleAppearance.VIEWS + "CapsuleContainer"));
        Class<?> entryType = Reflect.type(loader, alternate ? "x3.a" : prefix + "livealert.lib.EntryType");
        var binding = alternate ? null : NotificationPluginRepository.resolve(loader, entryType);
        Class<?> model = alternate ? Reflect.type(loader, "a5.i") : binding.wrapped();
        String filters = prefix + "seedling.data.interceptor.";
        FrontFilter front = alternate ? new FrontFilter(Reflect.type(loader, "b5.a"), null)
                : FrontFilter.resolve(Reflect.type(loader, filters + "a"), Reflect.type(loader, filters + "b"),
                        Reflect.type(loader, filters + "c"));
        Method filter = Reflect.uniqueMethod(front.owner(), method -> method.getName().equals("a")
                && method.getReturnType() == boolean.class && method.getParameterCount() == 2
                && method.getParameterTypes()[0] == model);
        // R8 renames the enum field; its serialized enum name stays stable.
        statusBar = java.util.Arrays.stream(entryType.getEnumConstants())
                .filter(value -> value instanceof Enum<?> item && item.name().equals("ENTRY_STATUS_BAR"))
                .findFirst().orElseThrow(() -> new NoSuchFieldException("Status-bar entry"));
        Method entry = Reflect.method(model, "k", entryType);
        Field suppressed = Reflect.field(model, "e", boolean.class);
        Method proceed = Reflect.method(filter.getParameterTypes()[1], alternate ? "c" : "g", boolean.class, model);
        Class<?> repositoryType = alternate ? Reflect.type(loader, "a5.h") : binding.singleton().getType();
        repository = alternate ? Reflect.uniqueField(repositoryType,
                field -> !Reflect.instance(field) && field.getType() == repositoryType) : binding.singleton();
        refreshEntry = Reflect.method(repositoryType, "c", void.class, entryType, boolean.class);
        hooks.add(module.hook(filter).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
            Object item = chain.getArg(0);
            // This synthetic class also implements deep-brick and super-power interceptors.
            if (!enabled.getAsBoolean() || front.kind() != null && front.kind().getInt(chain.getThisObject()) != 0
                    || Reflect.call(entry, item) != statusBar) return chain.proceed();
            suppressed.setBoolean(item, false);
            return Reflect.call(proceed, chain.getArg(1), item);
        }));
    }

    record FrontFilter(Class<?> owner, Field kind) {
        static FrontFilter resolve(Class<?> front, Class<?> multiplex, Class<?> api) throws ReflectiveOperationException {
            if (api.isAssignableFrom(front)) {
                front.getDeclaredConstructor();
                Reflect.field(front, "a", boolean.class);
                return new FrontFilter(front, null);
            }
            // In the older layout the front filter shares a class with two unrelated policy branches.
            Reflect.field(front, "b", multiplex);
            if (!api.isAssignableFrom(multiplex)) throw new NoSuchMethodException("Native foreground interceptor");
            return new FrontFilter(multiplex, Reflect.field(multiplex, "a", int.class));
        }
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
