package dev.lsposed.fluidcloud;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/** Resolve the native entry listener together with the repository that owns its wrapped models. */
record NotificationPluginRepository(Field singleton, Class<?> wrapped, Constructor<?> listener, Method publish) {
    static NotificationPluginRepository resolve(ClassLoader loader, Class<?> entry) throws ReflectiveOperationException {
        String base = "com.oplus.systemui.plugins.seedling.";
        Class<?> controller = Reflect.type(loader, base + "capsule.data.repo.e");
        Class<?> wrapped = Reflect.field(controller, "a", Object.class).getType();
        var listeners = new ArrayList<Class<?>>();
        for (String name : List.of("d", "e")) {
            try { listeners.add(Reflect.type(loader, base + "data.interactor." + name)); }
            catch (ClassNotFoundException ignored) { }
        }
        return bind(entry, wrapped, listeners);
    }

    static NotificationPluginRepository bind(Class<?> entry, Class<?> wrapped, List<Class<?>> listeners)
            throws ReflectiveOperationException {
        NotificationPluginRepository binding = null;
        for (Class<?> type : listeners) {
            for (Constructor<?> constructor : type.getDeclaredConstructors()) {
                Class<?>[] args = constructor.getParameterTypes();
                if (args.length != 2 || args[1] != entry) continue;
                NotificationPluginRepository candidate;
                try {
                    Class<?> repo = args[0];
                    Field singleton = Reflect.uniqueField(repo, f -> !Reflect.instance(f) && f.getType() == repo);
                    // The captured outer repository is a synthetic field in both DEX layouts.
                    if (!Reflect.instance(Reflect.field(type, "c", repo))) continue;
                    Reflect.uniqueField(type, f -> Reflect.instance(f) && f.getType() == entry);
                    Reflect.method(repo, "b", void.class, wrapped, boolean.class);
                    Reflect.method(repo, "c", void.class, entry, boolean.class);
                    Method publish = Reflect.method(type, "b", void.class, List.class);
                    if (java.lang.reflect.Modifier.isStatic(publish.getModifiers())) continue;
                    candidate = new NotificationPluginRepository(singleton, wrapped, constructor, publish);
                } catch (NoSuchFieldException | NoSuchMethodException incomplete) {
                    continue;
                }
                if (binding != null) throw new NoSuchMethodException("Ambiguous native notification repository");
                constructor.setAccessible(true);
                binding = candidate;
            }
        }
        if (binding == null) throw new NoSuchMethodException("No native notification repository for " + wrapped.getName());
        return binding;
    }
}
