package dev.howard.aodenhance;

import java.lang.reflect.Method;
import java.lang.reflect.Field;

/** Firmware symbol discovery, kept independent of Android/Xposed initialization. */
final class FirmwareTargets {
    interface Lookup { Class<?> load(String name) throws ClassNotFoundException; }
    private final Lookup lookup;
    FirmwareTargets(Lookup lookup) { this.lookup = lookup; }
    boolean separateBackgroundDim() {
        return optional("com.oplus.keyguard.ui.OplusKeyguardStyleClock") != null
                && optional("com.oplus.systemui.keyguard.ui.view.section.OplusKgdMaskSection") != null;
    }

    Class<?> gestureCallback() throws ClassNotFoundException {
        return firstAvailable(
                "com.oplus.systemui.aod.scene.PanoramicAodGestureController$PanoramicAodGestureCallback",
                "com.oplus.systemui.aod.scene.PanoramicAodSingleClickWakeUpController$PanoramicAodSingleClickWakeUpCallback");
    }

    ClockRoot clockRoot() throws ReflectiveOperationException {
        Class<?> modern = optional("com.oplus.keyguard.ui.OplusKeyguardStyleClock");
        // ColorOS 17's controller calls KeyguardPlugin directly, bypassing the View wrapper.
        if (modern != null) {
            Class<?> plugin = lookup.load("com.oplus.keyguard.plugin.KeyguardPlugin");
            Field container = plugin.getDeclaredField("pluginContainer");
            container.setAccessible(true);
            return new ClockRoot(plugin.getDeclaredMethod("setUp", int.class, int.class), container);
        }
        return new ClockRoot(lookup.load("com.oplus.keyguard.OplusKeyguardStyleClock")
                .getDeclaredMethod("onAttachedToWindow"), null);
    }

    PluginViews pluginViews() throws ReflectiveOperationException {
        Class<?> modern = optional("com.oplus.keyguard.plugin.AbstractSubPlugin");
        if (modern != null) return new PluginViews(modern.getDeclaredMethod("getView", int.class), 0);
        return new PluginViews(lookup.load("com.oplus.keyguard.plugin.KeyguardPlugin")
                .getDeclaredMethod("getView", int.class), 16);
    }

    private Class<?> optional(String name) {
        try { return lookup.load(name); }
        catch (ClassNotFoundException absent) { return null; }
    }

    private Class<?> firstAvailable(String modern, String legacy) throws ClassNotFoundException {
        Class<?> candidate = optional(modern);
        return candidate != null ? candidate : lookup.load(legacy);
    }

    record PluginViews(Method method, int widgetRootKind) {
        boolean isWidgetRoot(String owner, int kind) {
            return "com.oplus.keyguard.plugin.WidgetPlugin".equals(owner) && kind == widgetRootKind;
        }
    }

    record ClockRoot(Method method, Field container) {
        Object root(Object receiver) throws IllegalAccessException {
            return container == null ? receiver : container.get(receiver);
        }
    }
}
