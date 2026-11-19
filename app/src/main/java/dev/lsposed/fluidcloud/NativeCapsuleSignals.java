package dev.lsposed.fluidcloud;

import android.graphics.drawable.Drawable;
import android.view.View;
import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.Set;
import java.util.function.BiConsumer;

/** Observes plugin-owned overrides; never installs a framework View invalidation hook. */
final class NativeCapsuleSignals {
    enum Change { PIXELS, GEOMETRY, LAYOUT }
    private final Set<Class<?>> seen = new HashSet<>();
    private final Set<Method> methods = new HashSet<>();
    private final BiConsumer<Method, Change> install;
    NativeCapsuleSignals(BiConsumer<Method, Change> install) { this.install = install; }
    void observe(View view) {
        if (seen.add(view.getClass())) {
            observe(view.getClass(), "requestLayout", Change.LAYOUT);
            observe(view.getClass(), "invalidateDrawable", Change.PIXELS, Drawable.class);
            if (view.getClass().getSimpleName().equals("CapsuleView")) {
                observe(view.getClass(), "b", Change.GEOMETRY, float.class);
                observe(view.getClass(), "setHidden", Change.LAYOUT, boolean.class);
                observe(view.getClass(), "setCapsuleDrawableWidth", Change.LAYOUT, int.class);
            }
        }
    }
    private void observe(Class<?> type, String name, Change change, Class<?>... args) {
        try {
            Method method = Reflect.method(type, name, void.class, args);
            // Reflect.method searches parents. Inherited framework implementations
            // would turn this supposedly local observer into a global hot hook.
            String owner = method.getDeclaringClass().getName();
            if (owner.startsWith("android.") || owner.startsWith("androidx.")) return;
            if (methods.add(method)) install.accept(method, change);
        } catch (NoSuchMethodException ignored) { /* Root traversal remains the fallback. */ }
    }
}
