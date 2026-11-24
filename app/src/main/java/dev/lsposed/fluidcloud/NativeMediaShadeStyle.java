package dev.lsposed.fluidcloud;

import android.os.Bundle;
import android.view.View;
import io.github.libxposed.api.XposedInterface;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.function.BooleanSupplier;

/** Selects the OEM stack presentation only on the media renderer mounted in the unlocked shade. */
final class NativeMediaShadeStyle {
    private static final int STACK = 0;
    private final Field model, config;
    private final Method wrapperForView, getContent, prepare;
    private View boundContent;
    private final Class<?> wrapperType;
    private final XposedInterface.HookHandle hook;
    private final Presentation presentation = new Presentation(this::render);

    NativeMediaShadeStyle(HookEntry module, Class<?> entryType, BooleanSupplier unlocked)
            throws ReflectiveOperationException {
        ClassLoader loader = entryType.getClassLoader();
        Class<?> wrapper = Reflect.type(loader, "com.oplus.systemui.plugins.livealert.lib.h");
        wrapperType = Reflect.type(loader, "com.oplus.systemui.livealert.classic.origin.o");
        model = Reflect.field(entryType, "a", Object.class);
        wrapperForView = Reflect.signature(model.getType(), wrapper, View.class);
        config = Reflect.uniqueField(wrapperType, f -> Reflect.instance(f) && f.getType() == Bundle.class);
        getContent = Reflect.method(entryType, "getCustomView", View.class);
        prepare = Reflect.method(wrapperType, "onPreChange", void.class, Bundle.class);
        hook = module.hook(prepare).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
            Object renderer = chain.getThisObject();
            if (!presentation.owns(renderer)) return chain.proceed();
            Bundle event = (Bundle) chain.getArg(0);
            Bundle next = presentation.event(event, unlocked.getAsBoolean());
            return next == event ? chain.proceed() : chain.proceed(new Object[]{next});
        });
    }

    boolean show(Object entry) {
        View content = (View) Reflect.call(getContent, entry);
        if (content == boundContent && presentation.current != null) return true;
        // The native list can replace the content in an existing host row during a track change.
        Object renderer = Reflect.call(wrapperForView, Reflect.get(model, entry), content);
        if (!wrapperType.isInstance(renderer)) {
            release();
            return false;
        }
        boundContent = content;
        Bundle options = (Bundle) Reflect.get(config, renderer);
        presentation.show(renderer, options.getInt("toState", STACK));
        return true;
    }

    void release() {
        boundContent = null;
        presentation.release();
    }

    void close() {
        try { release(); }
        finally { hook.unhook(); }
    }

    private void render(Object renderer, int from, int to) {
        Bundle event = new Bundle();
        event.putString("method", "immersiveStateChange");
        event.putInt("cardSize", 1);
        event.putInt("fromState", from);
        event.putInt("toState", to);
        event.putBoolean("animate", false);
        Reflect.call(prepare, renderer, event);
    }

    @FunctionalInterface
    interface Render { void apply(Object renderer, int from, int to); }

    static final class Presentation {
        private final Render render;
        private Object current;
        private int requested;

        Presentation(Render render) { this.render = render; }

        boolean owns(Object renderer) { return current != null && current == renderer; }

        void show(Object renderer, int nativeTarget) {
            if (owns(renderer)) return;
            release();
            // Dispatch before taking ownership so our own call goes straight to the OEM.
            render.apply(renderer, nativeTarget, STACK);
            current = renderer;
            requested = nativeTarget;
        }

        Bundle event(Bundle original, boolean unlocked) {
            if (!original.containsKey("toState")) return original;
            if (!unlocked) {
                // A genuine keyguard request takes precedence over any saved target.
                current = null;
                return original;
            }
            requested = original.getInt("toState");
            if (requested == STACK && original.getInt("fromState") == STACK) return original;
            Bundle event = new Bundle(original);
            event.putInt("fromState", STACK);
            event.putInt("toState", STACK);
            event.putBoolean("animate", false);
            return event;
        }

        void release() {
            Object previous = current;
            current = null;
            if (previous != null && requested != STACK) render.apply(previous, STACK, requested);
        }
    }
}
