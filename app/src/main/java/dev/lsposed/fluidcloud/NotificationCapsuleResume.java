package dev.lsposed.fluidcloud;

import android.graphics.Rect;
import android.view.View;
import android.view.ViewGroup;
import io.github.libxposed.api.XposedInterface;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;

/** One-shot handoff for the selected status-bar notification. Keyguard keeps its own renderer. */
final class NotificationCapsuleResume {
    private final NotificationCloudFeature feature;
    private final Class<?> container;
    private final Field key, state, owner, candidates, selectedKey, candidateKey, service, urgent, callStyle, uiKey;
    private final Method select;
    private final XposedInterface.HookHandle createHook, bindHook;
    private WeakReference<View> host = new WeakReference<>(null);
    private String saved;
    private boolean sampledBeforeSleep;
    private View waiting;
    private Rect previousClip;
    private final Runnable timeout = this::reset;
    private final android.view.ViewTreeObserver.OnPreDrawListener checkDisplayed = () -> {
        finishIfDisplayed();
        return true;
    };

    NotificationCapsuleResume(NotificationCloudFeature feature, ClassLoader loader) throws ReflectiveOperationException {
        this.feature = feature;
        container = Reflect.type(loader, NativeCapsuleAppearance.VIEWS + "CapsuleContainer");
        boolean legacy = ColorOs16Plugin.capsule(container);
        state = Reflect.field(container, legacy ? "v" : "m", Enum.class);
        key = Reflect.field(container, legacy ? "u" : "l", String.class);
        owner = Reflect.field(container, legacy ? "w" : "n", Object.class);
        Class<?> vm = owner.getType();
        Class<?> item = Reflect.type(loader, legacy ? "h4.a" : "com.oplus.systemui.plugins.seedling.capsule.data.model.a");
        Class<?> ui = Reflect.type(loader, legacy ? "k4.f" : "com.oplus.systemui.plugins.seedling.capsule.ui.model.f");
        boolean earlier = !legacy && vm.getSimpleName().equals("u");
        candidates = Reflect.field(vm, earlier ? "m" : "l", List.class);
        selectedKey = Reflect.field(vm, earlier ? "n" : "m", String.class);
        candidateKey = Reflect.field(item, "a", String.class);
        service = Reflect.field(item, "v", String.class);
        urgent = Reflect.field(item, "e", boolean.class);
        callStyle = Reflect.field(item, "E", boolean.class);
        uiKey = Reflect.field(ui, "b", String.class);
        select = Reflect.method(vm, "G", void.class, String.class, String.class, boolean.class, boolean.class);
        Method create = Reflect.method(vm, legacy ? "i" : "j", ui, state.getType());
        Method bind = Reflect.method(container, legacy ? "i" : "h", void.class, ui, boolean.class);
        createHook = feature.module.hook(create).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
            if (waiting != null && feature.unlocked() && normal(chain.getArg(0))
                    && Reflect.get(owner, waiting) == chain.getThisObject() && feature.store.get(saved) != null) {
                Object match = null;
                for (Object model : (List<?>) Reflect.get(candidates, chain.getThisObject())) {
                    if (saved.equals(Reflect.get(candidateKey, model))) match = model;
                    else if (urgent.getBoolean(model) || callStyle.getBoolean(model)) {
                        // Preserve a new native priority alert over the remembered notification.
                        reset();
                        break;
                    }
                }
                if (waiting != null && match != null)
                    Reflect.call(select, chain.getThisObject(), saved, Reflect.get(service, match), false, false);
            }
            return chain.proceed();
        });
        try {
            bindHook = feature.module.hook(bind).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
                if (waiting == null || chain.getThisObject() != waiting || !feature.unlocked()
                        || !saved.equals(Reflect.get(uiKey, chain.getArg(0)))) return chain.proceed();
                Object result = chain.proceed(new Object[]{chain.getArg(0), false});
                finishIfDisplayed();
                return result;
            });
        } catch (RuntimeException | LinkageError failure) {
            createHook.unhook();
            throw failure;
        }
    }

    void host(View root) {
        if (host.get() == root) return;
        reset();
        host = new WeakReference<>(root);
    }

    void remember() {
        if (sampledBeforeSleep) {
            sampledBeforeSleep = false;
            return;
        }
        sampleSelection();
    }

    void beforeSleep() {
        sampleSelection();
        sampledBeforeSleep = true;
    }

    private void sampleSelection() {
        reset();
        View view = findContainer(host.get());
        if (view == null || !normal(Reflect.get(state, view))) return;
        String current = (String) Reflect.get(key, view);
        if (current != null && feature.store.ready(current)) saved = current;
    }

    void startUnlock() {
        if (saved == null || waiting != null) return;
        View view = findContainer(host.get());
        Object entry = feature.liveEntry(saved);
        if (view == null || entry == null || !feature.convertibleAfterUnlock(entry)) { reset(); return; }
        // Hide only this capsule's pixels while the plugin repopulates its candidate list.
        // The status bar, lockscreen, notifications and input pipeline keep rendering normally.
        waiting = view;
        previousClip = view.getClipBounds();
        view.setClipBounds(new Rect());
        view.getViewTreeObserver().addOnPreDrawListener(checkDisplayed);
        feature.main.postDelayed(timeout, 850L);
    }

    private void finishIfDisplayed() {
        if (waiting == null || !feature.unlocked() || !feature.store.ready(saved)
                || !normal(Reflect.get(state, waiting)) || !saved.equals(Reflect.get(key, waiting))) return;
        Object vm = Reflect.get(owner, waiting);
        if (vm != null && saved.equals(Reflect.get(selectedKey, vm))) {
            for (Object item : (List<?>) Reflect.get(candidates, vm)) {
                if (saved.equals(Reflect.get(candidateKey, item))) { reset(); break; }
            }
        }
    }

    void removed(String notificationKey) { if (notificationKey.equals(saved)) reset(); }

    void reset() {
        cancelUnlock();
        saved = null;
        sampledBeforeSleep = false;
    }

    void cancelUnlock() {
        feature.main.removeCallbacks(timeout);
        if (waiting != null) {
            if (waiting.getViewTreeObserver().isAlive())
                waiting.getViewTreeObserver().removeOnPreDrawListener(checkDisplayed);
            Rect clip = waiting.getClipBounds();
            if (clip != null && clip.isEmpty()) waiting.setClipBounds(previousClip);
        }
        previousClip = null;
        waiting = null;
    }

    void close() {
        reset();
        bindHook.unhook();
        createHook.unhook();
    }

    private static boolean normal(Object state) { return state instanceof Enum<?> value && "NORMAL".equals(value.name()); }

    private View findContainer(View view) {
        if (container.isInstance(view)) return view;
        if (view instanceof ViewGroup group) for (int i = 0; i < group.getChildCount(); i++) {
            View child = findContainer(group.getChildAt(i));
            if (child != null) return child;
        }
        return null;
    }
}
