package dev.lsposed.fluidcloud;

import android.content.Context;
import dalvik.system.DexFile;
import dev.howard.colorosenhance.diagnostics.SeedlingSdkDiagnostics;
import java.lang.reflect.Field;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.function.Predicate;

/** A one-time, package-scoped search of the plugin's own DEX. No class initialization or hot-path scans. */
final class PluginClasses {
    private static final ClassValue<PluginClasses> INDEX = new ClassValue<>() {
        @Override protected PluginClasses computeValue(Class<?> anchor) {
            try { return new PluginClasses(anchor.getClassLoader()); }
            catch (ReflectiveOperationException error) { throw new IllegalStateException("Plugin DEX index unavailable", error); }
        }
    };
    private final ClassLoader loader;
    private final List<String> names;
    final List<String> paths;

    static PluginClasses of(Class<?> anchor) { return INDEX.get(anchor); }

    private PluginClasses(ClassLoader loader) throws ReflectiveOperationException {
        this.loader = loader;
        var classes = new LinkedHashSet<String>();
        var files = new LinkedHashSet<String>();
        for (DexFile dex : dexFiles(loader)) {
            files.add(dex.getName());
            var entries = dex.entries();
            while (entries.hasMoreElements()) classes.add(entries.nextElement());
        }
        names = List.copyOf(classes);
        paths = List.copyOf(files);
    }

    private static List<DexFile> dexFiles(ClassLoader loader) throws ReflectiveOperationException {
        Object pathList = Reflect.get(Reflect.field(loader.getClass(), "pathList", Object.class), loader);
        Object[] elements = (Object[]) Reflect.get(Reflect.field(pathList.getClass(), "dexElements", Object[].class), pathList);
        var files = new java.util.ArrayList<DexFile>();
        for (Object element : elements) {
            var dex = (DexFile) Reflect.get(Reflect.field(element.getClass(), "dexFile", DexFile.class), element);
            if (dex != null) files.add(dex);
        }
        return files;
    }

    static SeedlingSdkDiagnostics.ArchivePaths seedlingPaths(ClassLoader plugin, Context host)
            throws ReflectiveOperationException {
        List<String> loaded = loadedSeedlingPaths(plugin);
        if (!loaded.isEmpty()) return new SeedlingSdkDiagnostics.ArchivePaths(loaded, true);
        Class<?> filePath = Reflect.type(plugin, "com.oplus.seedling.sdk.plugin.SeedlingConstants$PluginFilePath");
        var path = Reflect.method(filePath, "getPathPlugin", String.class, Context.class);
        var configured = new LinkedHashSet<String>();
        for (Context storage : List.of(host, host.createDeviceProtectedStorageContext())) {
            if (Reflect.call(path, null, storage) instanceof String archive) configured.add(archive);
        }
        return new SeedlingSdkDiagnostics.ArchivePaths(List.copyOf(configured), false);
    }

    private static List<String> loadedSeedlingPaths(ClassLoader plugin) throws ReflectiveOperationException {
        Class<?> sdk = Reflect.type(plugin, "com.oplus.seedling.sdk.SeedlingSdk");
        var getter = Reflect.uniqueMethod(sdk, method -> method.getName().equals("getSeedlingPluginClassLoader")
                && method.getParameterCount() == 0 && ClassLoader.class.isAssignableFrom(method.getReturnType()));
        var active = (ClassLoader) Reflect.call(getter, Reflect.get(Reflect.field(sdk, "INSTANCE", sdk), null));
        if (active == null || active == sdk.getClassLoader()) return List.of();
        return dexFiles(active).stream().map(DexFile::getName).filter(path -> path != null).distinct().toList();
    }

    Class<?> unique(String prefix, Predicate<Class<?>> matches) throws ReflectiveOperationException {
        Class<?> found = null;
        for (String name : names) {
            if (!name.startsWith(prefix)) continue;
            // Resolution stays within the actual plugin loader, without running constructors or static initializers.
            Class<?> type;
            try {
                type = Reflect.type(loader, name);
                if (!matches.test(type)) continue;
            } catch (ClassNotFoundException | LinkageError unavailable) { continue; }
            if (found != null) throw new NoSuchMethodException("Ambiguous plugin contract in " + prefix);
            found = type;
        }
        if (found == null) throw new NoSuchMethodException("No matching plugin contract in " + prefix);
        return found;
    }

    static boolean has(Class<?> type, Class<?> result, Class<?>... args) {
        for(var method:type.getDeclaredMethods())
            if(!method.isBridge()&&!method.isSynthetic()&&method.getReturnType()==result
                    &&java.util.Arrays.equals(method.getParameterTypes(),args))return true;
        return false;
    }

    static Class<?> outline(ClassLoader loader) throws ReflectiveOperationException {
        Class<?> anchor = Reflect.type(loader, NativeCameraCard.VIEWS + "CardView");
        return outline(anchor);
    }

    static Class<?> outline(Class<?> anchor) throws ReflectiveOperationException {
        return of(anchor).unique("com.oplus.systemui.plugins.common.util.", type -> {
            if (!android.view.ViewOutlineProvider.class.isAssignableFrom(type)) return false;
            try {
                Reflect.uniqueField(type, field -> Reflect.instance(field) && field.getType() == android.graphics.Rect.class);
                type.getDeclaredConstructor(android.graphics.Rect.class,
                        Reflect.type(anchor.getClassLoader(), "kotlin.Triple"));
                return true;
            } catch (ReflectiveOperationException error) { return false; }
        });
    }
}
