package dev.lsposed.fluidcloud;

import android.view.View;
import io.github.libxposed.api.XposedInterface;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.List;
import java.util.WeakHashMap;

/** Completes the native media pager's selection handoff in the unlocked notification row. */
final class NativeMediaShadePager {
    private record Firmware(String section, String callback, String pending, String model, String adapter) { }
    private static final Firmware CURRENT = new Firmware("j1", "a7", "w", "m", "v");
    private static final Firmware EARLIER = new Firmware("e1", "Y6", "u", "l", "t");

    private final HookEntry module;
    private final Class<?> sectionType;
    private final Field callbackOwner, pending, pager, indicator, model, configFlow, pageIndex, keyguard;
    private final Method value, committed, commitCaller, selected, currentPage, scrollState, dotCount, setDot;
    // The host may acquire a section after its first list commit. Never retain disposed sections.
    private final WeakHashMap<Object, Integer> dotCounts = new WeakHashMap<>();

    static NativeMediaShadePager install(HookEntry module, ClassLoader loader, List<XposedInterface.HookHandle> hooks)
            throws ReflectiveOperationException {
        NativeMediaShadePager bridge;
        try { bridge = new NativeMediaShadePager(module, loader, CURRENT); }
        catch (ReflectiveOperationException unavailable) { bridge = new NativeMediaShadePager(module, loader, EARLIER); }
        bridge.install(hooks);
        return bridge;
    }

    private NativeMediaShadePager(HookEntry module, ClassLoader loader, Firmware fw) throws ReflectiveOperationException {
        this.module = module;
        String prefix = "com.oplus.systemui.plugins.";
        sectionType = Reflect.type(loader, prefix + "shared.template.section.media." + fw.section);
        Class<?> callback = Reflect.type(loader, prefix + "r8." + fw.callback);
        callback.getDeclaredConstructor(sectionType);
        callbackOwner = Reflect.field(callback, "b", Object.class);
        selected = Reflect.method(callback, "c", void.class, int.class);
        committed = Reflect.method(sectionType, "r", void.class, sectionType, int.class);
        if (!Modifier.isStatic(committed.getModifiers())) throw new NoSuchMethodException("media page commit");
        Class<?> adapter = Reflect.field(sectionType, fw.adapter, Object.class).getType();
        Method submit = Reflect.uniqueMethod(adapter, m -> m.getName().equals("b") && m.getReturnType() == void.class
                && m.getParameterCount() == 2 && m.getParameterTypes()[0] == List.class);
        commitCaller = Reflect.method(submit.getParameterTypes()[1], "invoke", Object.class);
        Class<?> pagerType = Reflect.type(loader, "androidx.viewpager2.widget.ViewPager2");
        Class<?> indicatorType = Reflect.type(loader, "com.coui.appcompat.indicator.COUIPageIndicator");
        pager = Reflect.uniqueField(sectionType, f -> Reflect.instance(f) && f.getType() == pagerType);
        indicator = Reflect.uniqueField(sectionType, f -> Reflect.instance(f) && f.getType() == indicatorType);
        pending = Reflect.field(sectionType, fw.pending, boolean.class);
        currentPage = Reflect.method(pagerType, "getCurrentItem", int.class);
        scrollState = Reflect.method(pagerType, "getScrollState", int.class);
        dotCount = Reflect.method(indicatorType, "getDotsCount", int.class);
        setDot = Reflect.method(indicatorType, "setCurrentPosition", void.class, int.class);
        model = Reflect.field(sectionType, fw.model, Object.class);
        configFlow = Reflect.field(model.getType(), "b", Object.class);
        value = configFlow.getType().getMethod("getValue");
        Class<?> config = Reflect.type(loader, prefix + "shared.data.model.h");
        pageIndex = Reflect.field(config, "u", int.class);
        keyguard = Reflect.field(config, "v", boolean.class);
    }

    private void install(List<XposedInterface.HookHandle> hooks) {
        module.deoptimize(commitCaller);
        var selection = module.hook(selected).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
            Object section = Reflect.get(callbackOwner, chain.getThisObject());
            if (module.mediaShadeActive() && sectionType.isInstance(section) && pending.getBoolean(section)) {
                View view = (View) Reflect.get(pager, section);
                if (module.mediaShadeRow(view) != null && userSelecting(keyguard.getBoolean(config(section)),
                        (int) Reflect.call(scrollState, view))) {
                    // The OEM otherwise drops onMediaIndexChanged until keyguard next opens.
                    pending.setBoolean(section, false);
                }
            }
            return chain.proceed();
        });
        try {
            hooks.add(module.hook(committed).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
                if (!module.mediaShadeEnabled()) return chain.proceed();
                Object section = chain.getArg(0);
                dotCounts.putIfAbsent(section, -1);
                View view = (View) Reflect.get(pager, section);
                if (module.mediaShadeRow(view) == null) return chain.proceed();
                Object config = config(section);
                if (keyguard.getBoolean(config)) return chain.proceed();
                Object dots = Reflect.get(indicator, section);
                int count = (int) Reflect.call(dotCount, dots);
                int current = (int) Reflect.call(currentPage, view);
                int target = commitTarget((int) Reflect.call(scrollState, view), count, pageIndex.getInt(config), current);
                if (target < 0) return null;
                // The diff callback captured its index before the user's swipe. Use the live index.
                Object result = chain.proceed(new Object[]{section, target});
                int actual = (int) Reflect.call(currentPage, view);
                if (actual >= 0 && actual < count && (current != actual || dotCounts.get(section) != count)) {
                    Reflect.call(setDot, dots, actual);
                    dotCounts.put(section, count);
                }
                return result;
            }));
        } catch (RuntimeException | LinkageError failure) { selection.unhook(); throw failure; }
        hooks.add(selection);
    }

    private Object config(Object section) {
        return Reflect.call(value, Reflect.get(configFlow, Reflect.get(model, section)));
    }

    void restoreRow(View row) {
        for (var entry : dotCounts.entrySet()) {
            Object section = entry.getKey();
            View view = (View) Reflect.get(pager, section);
            if (module.mediaShadeRow(view) != row || Boolean.TRUE.equals(Reflect.get(keyguard, config(section)))) continue;
            // The indicator can restore its own position before its row becomes visible.
            entry.setValue(-1);
            Reflect.call(committed, null, section, Reflect.get(pageIndex, config(section)));
        }
    }

    static boolean userSelecting(boolean nativeKeyguard, int scrollState) {
        return !nativeKeyguard && (scrollState == 1 || scrollState == 2);
    }

    static int commitTarget(int scrollState, int count, int latest, int current) {
        if (scrollState != 0 || count <= 0) return -1;
        if (latest >= 0 && latest < count) return latest;
        return Math.clamp(current, 0, count - 1);
    }
}
