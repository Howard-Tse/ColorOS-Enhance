package dev.lsposed.fluidcloud;

import android.view.View;
import android.view.ViewGroup;
import dev.howard.colorosenhance.diagnostics.DiagnosticPoint;
import io.github.libxposed.api.XposedInterface;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/** Reuses the OEM text and lyric updates when a native media notification row is shown unlocked. */
final class NativeMediaShadeText {
    private static final String MEDIA = "com.oplus.systemui.plugins.shared.template.section.media.";
    private static final String COLLECT = MEDIA + "MediaPlayerSection$updatePageContent$1$";

    private record Firmware(String section, String model, String keyguard, String sectionState,
                            String textCollector, String lyricCollector, String textUpdate, String lyricUpdate) { }
    private static final Firmware CURRENT = new Firmware("x1", "l", "P", "d", "8$2", "11$2", "Q", "L");
    private static final Firmware EARLIER = new Firmware("s1", "k", "O", "c", "7$2", "10$2", "O", "J");

    private final HookEntry module;
    private final Field model, keyguardFlow, sectionState;
    private final Method view, notification, keyguardValue, alternateFlow, alternateValue;
    private final Observer metadata, position;
    private boolean observed;

    static void install(HookEntry module, ClassLoader loader, List<XposedInterface.HookHandle> handles)
            throws ReflectiveOperationException {
        NativeMediaShadeText adapter;
        try { adapter = new NativeMediaShadeText(module, loader, CURRENT); }
        catch (ReflectiveOperationException unavailable) { adapter = new NativeMediaShadeText(module, loader, EARLIER); }
        adapter.install(handles);
    }

    private NativeMediaShadeText(HookEntry module, ClassLoader loader, Firmware firmware) throws ReflectiveOperationException {
        this.module = module;
        Class<?> section = Reflect.type(loader, MEDIA + firmware.section);
        model = Reflect.field(section, firmware.model, Object.class);
        keyguardFlow = Reflect.field(model.getType(), firmware.keyguard, Object.class);
        keyguardValue = Reflect.method(keyguardFlow.getType(), "getValue", Object.class);
        sectionState = Reflect.field(model.getType(), firmware.sectionState, Object.class);
        alternateFlow = sectionState.getType().getMethod("g");
        alternateValue = alternateFlow.getReturnType().getMethod("getValue");
        view = Reflect.method(section, "h", ViewGroup.class);
        notification = Reflect.method(section, "o", boolean.class);
        metadata = new Observer(loader, section, firmware.textCollector, firmware.textUpdate, false);
        position = new Observer(loader, section, firmware.lyricCollector, firmware.lyricUpdate, true);
    }

    private void install(List<XposedInterface.HookHandle> handles) {
        List<XposedInterface.HookHandle> installed = new ArrayList<>();
        try {
            for (Observer observer : List.of(metadata, position)) {
                observer.deoptimizeCallers();
                installed.add(module.hook(observer.callback).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                        .intercept(chain -> {
                            Object result = chain.proceed();
                            observer.update(chain.getThisObject());
                            return result;
                        }));
            }
        } catch (RuntimeException | LinkageError failure) {
            for (var handle : installed) handle.unhook();
            throw failure;
        }
        handles.addAll(installed);
        module.report(DiagnosticPoint.MEDIA_SHADE_TEXT, DiagnosticPoint.State.INSTALLED);
    }

    private final class Observer {
        private final Class<?> type;
        private final Field owner;
        private final Method callback, render;
        private final boolean positionOnly;

        Observer(ClassLoader loader, Class<?> section, String suffix, String update, boolean positionOnly)
                throws ReflectiveOperationException {
            this.positionOnly = positionOnly;
            type = Reflect.type(loader, COLLECT + suffix);
            owner = Reflect.field(type, "this$0", section);
            callback = Reflect.method(type, "invokeSuspend", Object.class, Object.class);
            render = Reflect.method(section, update, void.class, boolean.class);
        }

        void deoptimizeCallers() {
            // The Kotlin bridge can inline the small collector on cached firmware builds.
            for (Method caller : type.getDeclaredMethods()) {
                if (caller.getName().equals("invoke") && caller.getParameterCount() == 2) module.deoptimize(caller);
            }
        }

        void update(Object collector) {
            if (!module.mediaShadeActive()) return;
            try {
                Object section = Reflect.get(owner, collector);
                View row = module.mediaShadeRow((View) Reflect.call(view, section));
                if (row == null || !(boolean) Reflect.call(notification, section)) return;
                boolean shown = row.isShown() && row.hasWindowFocus();
                if (positionOnly && !shown) return;
                Object vm = Reflect.get(model, section);
                boolean keyguard = Boolean.TRUE.equals(Reflect.call(keyguardValue, Reflect.get(keyguardFlow, vm)));
                Object fallback = Reflect.call(alternateFlow, Reflect.get(sectionState, vm));
                boolean nativeUpdate = Boolean.TRUE.equals(Reflect.call(alternateValue, fallback));
                if (!needsReplay(keyguard, nativeUpdate, positionOnly, shown)) return;
                // Read the live view model through the original renderer. A hidden row can
                // take a metadata change immediately without animating offscreen.
                Reflect.call(render, section, !shown);
                if (!observed) {
                    observed = true;
                    module.report(DiagnosticPoint.MEDIA_SHADE_TEXT, DiagnosticPoint.State.ACTIVE);
                }
            } catch (RuntimeException | LinkageError failure) {
                observed = false;
                module.report(DiagnosticPoint.MEDIA_SHADE_TEXT, DiagnosticPoint.State.FAILED);
            }
        }
    }

    static boolean needsReplay(boolean keyguard, boolean nativeUpdate, boolean positionOnly, boolean shown) {
        return !keyguard && !nativeUpdate && (!positionOnly || shown);
    }
}
