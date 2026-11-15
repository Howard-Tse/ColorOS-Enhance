package dev.howard.aodenhance;

import android.view.View;
import android.graphics.Rect;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import io.github.libxposed.api.XposedInterface.Hooker;
import io.github.libxposed.api.XposedInterface.Invoker;

final class FirmwareHooks {
    private static final String AOD = "com.oplus.systemui.aod.";
    private final AodModule module;
    private final ClassLoader loader;
    private final ViewOverrides views;
    private final FirmwareTargets targets;

    FirmwareHooks(AodModule module, ClassLoader loader, ViewOverrides views) {
        this.module = module; this.loader = loader; this.views = views;
        targets = new FirmwareTargets(this::type);
    }
    void install() {
        if (targets.separateBackgroundDim()) {
            views.useWallpaperBackgroundDim();
            module.info("背景压暗由壁纸引擎的独立背景节点处理");
        }
        independent("单击唤醒", () -> installMethod(
                targets.gestureCallback().getName(),
                "onClick", 1, chain -> {
                    Config c = module.config;
                    module.trace("单击-传感器", "blocked=" + (c.enabled && c.blockTap));
                    if (c.enabled && c.blockTap) return null;
                    return chain.proceed();
                }));
        independent("全景双击唤醒", () -> {
            String owner = targets.gestureCallback().getName();
            Method click = type(owner).getDeclaredMethod("onClick", type("com.android.systemui.keyguard.OplusBlackScreenGestureEvent"));
            click.setAccessible(true);
            var nativeClick = module.getInvoker(click).setType(Invoker.Type.ORIGIN);
            installMethod(owner, "onDoubleClick", 1, chain -> {
                if (!AodPolicy.blockSingleTap(module.config, views.isPanoramic())) return chain.proceed();
                module.trace("双击-传感器", "nativeWakeRequested=true");
                // Native dispatcher has already checked its 500 ms window and point distance.
                // Keep native proximity/screen/AOD guards; bypass only our single-tap hook.
                return nativeClick.invoke(chain.getThisObject(), chain.getArg(0));
            });
            installMethod(owner, "isSupportGesture", 1, chain -> AodPolicy.supportGesture(module.config,
                    views.isPanoramic(), ((Number) chain.getArg(0)).intValue(), (Boolean) chain.proceed()));
            String dispatcher = "com.oplus.systemui.keyguard.gesture.OplusBlackScreenGestureControllExImpl";
            for (Method method : type(dispatcher).getDeclaredMethods()) {
                if (method.getName().equals("access$onReceiveGesture") || method.getName().equals("findTargetCallback"))
                    module.deoptimize(method);
            }
            module.deoptimize(type("com.android.systemui.keyguard.OplusBlackScreenGestureCallback")
                    .getDeclaredMethod("onGesture", type("com.android.systemui.keyguard.OplusBlackScreenGestureEvent")));
        });
        independent("触摸单击唤醒", () -> installMethod(
                "com.oplus.systemui.keyguard.gesture.OplusDoubleClickSleep$OnDoubleClickListener",
                "onSingleTapConfirmed", 1, chain -> {
                    boolean blocked = AodPolicy.blockSingleTap(module.config, views.isPanoramic());
                    module.trace("单击-触摸", "panoramic=" + views.isPanoramic() + " blocked=" + blocked);
                    return blocked ? true : chain.proceed();
                }));
        independent("亮度表", () -> {
            String owner = AOD + "sensor.backlight.OplusPanoramicAODLightController";
            installMethod(owner, "getAodBrightnessNit", 0, chain -> {
                Object result = chain.proceed();
                if (!(result instanceof float[] nativeTable)) return result;
                float[] adjusted = AodPolicy.brightness(nativeTable, module.config);
                if (nativeTable.length > 0) module.trace("亮度表", "first=" + nativeTable[0] + " -> " + adjusted[0]);
                return adjusted;
            });
            // The private getter may have been inlined by ART in its immediate callers.
            for (Method method : type(owner).getDeclaredMethods()) {
                if (method.getName().equals("access$calculateBrightness") || method.getName().equals("aodLuxToLevel")
                        || method.getName().equals("aodLuxToNit")) module.deoptimize(method);
            }
        });
        independent("亮度计算诊断", () -> {
            String owner = AOD + "sensor.backlight.OplusPanoramicAODLightController";
            Field nit = type(owner).getDeclaredField("curBrightnessNit"); nit.setAccessible(true);
            Field level = type(owner).getDeclaredField("curBrightnessLevel"); level.setAccessible(true);
            Field cap = type(owner).getDeclaredField("curScreenBrightness"); cap.setAccessible(true);
            installMethod(owner, "access$calculateBrightness", 3, chain -> {
                Object result = chain.proceed();
                try { module.trace("亮度计算", "lux=" + chain.getArg(1) + " nit=" + nit.get(null)
                        + " level=" + level.get(null) + " screenCap=" + cap.get(null)); }
                catch (ReflectiveOperationException error) { module.warnOnce("亮度诊断读取失败", error); }
                return result;
            });
        });
        independent("亮度输出诊断", () -> installMethod(AOD + "display.BaseDisplayUtil", "setDozeScreenBrightness", 2, chain -> {
            module.trace("亮度输出", "nit=" + chain.getArg(0) + " level=" + chain.getArg(1));
            return chain.proceed();
        }));
        independent("图层透明度", () -> {
            Method alpha = View.class.getDeclaredMethod("setAlpha", float.class);
            module.hook(alpha).intercept(chain -> {
                float requested = ((Number) chain.getArg(0)).floatValue();
                float adjusted = views.interceptAlpha((View) chain.getThisObject(), requested);
                return adjusted == requested ? chain.proceed() : chain.proceed(new Object[]{adjusted});
            });
        });
        independent("元素裁剪", () -> module.hook(View.class.getDeclaredMethod("setClipBounds", Rect.class)).intercept(chain -> {
            Rect requested = (Rect) chain.getArg(0);
            Rect adjusted = views.interceptClip((View) chain.getThisObject(), requested);
            return chain.proceed(new Object[]{adjusted});
        }));
        independent("元素淡入淡出", () -> {
            module.hook(View.class.getDeclaredMethod("setTransitionAlpha", float.class)).intercept(chain -> {
                float requested = ((Number) chain.getArg(0)).floatValue();
                float adjusted = views.interceptTransitionAlpha((View) chain.getThisObject(), requested);
                return adjusted == requested ? chain.proceed() : chain.proceed(new Object[]{adjusted});
            });
            module.hook(View.class.getDeclaredMethod("getTransitionAlpha")).intercept(chain -> {
                Float nativeAlpha = views.nativeTransitionAlpha((View) chain.getThisObject());
                return nativeAlpha == null ? chain.proceed() : nativeAlpha;
            });
            views.enableTransitions();
        });
        independent("全景状态", () -> installMethod(AOD + "display.SmoothTransitionController", "updateCurrentUiState", 2, chain -> {
            // Set our scope before the firmware synchronously updates its view clients.
            Object state = chain.getArg(0);
            if (state instanceof Enum<?>) views.setUiState(((Enum<?>) state).name(), Boolean.TRUE.equals(chain.getArg(1)));
            return chain.proceed();
        }));
        independent("状态栏元素", () -> installMethod("com.android.systemui.statusbar.phone.KeyguardStatusBarView", "onFinishInflate", 0, chain -> {
            Object result = chain.proceed();
            try { views.statusInflated((View) chain.getThisObject()); }
            catch (RuntimeException error) { module.warnOnce("状态栏发现失败", error); }
            return result;
        }));
        independent("壁纸图层", () -> installMethod("com.android.systemui.shade.NotificationShadeWindowView", "onAttachedToWindow", 0, chain -> {
            Object result = chain.proceed();
            try { views.shadeAttached((View) chain.getThisObject()); }
            catch (RuntimeException error) { module.warnOnce("壁纸发现失败", error); }
            return result;
        }));
        independent("时钟日期", () -> {
            FirmwareTargets.ClockRoot target = targets.clockRoot();
            Method lifecycle = target.method();
            lifecycle.setAccessible(true);
            module.hook(lifecycle).intercept(chain -> {
                Object result = chain.proceed();
                try { views.clockRoot((View) target.root(chain.getThisObject())); }
                catch (ReflectiveOperationException | RuntimeException error) { module.warnOnce("时钟发现失败", error); }
                return result;
            });
        });
        independent("时钟小组件", () -> {
            FirmwareTargets.PluginViews target = targets.pluginViews();
            target.method().setAccessible(true);
            module.hook(target.method()).intercept(chain -> {
                Object result = chain.proceed();
                if (result instanceof View view) {
                    String owner = chain.getThisObject().getClass().getName();
                    int kind = ((Number) chain.getArg(0)).intValue();
                    try {
                        if (target.isWidgetRoot(owner, kind)) views.widgetView(view);
                        if (target.widgetRootKind() == 0 && kind == 0
                                && owner.equals("com.oplus.keyguard.plugin.ClockPlugin")) views.clockRoot(view);
                    } catch (RuntimeException error) { module.warnOnce("时钟与小组件发现失败", error); }
                }
                return result;
            });
        });
        independent("小组件调用路径", () -> {
            if (targets.pluginViews().widgetRootKind() == 16) {
                module.deoptimize(type("com.oplus.keyguard.plugin.ThemePlugin").getDeclaredMethod("attachWidgetsToClock"));
            } else {
                module.deoptimize(type("com.oplus.systemui.keyguard.clockstyle.KeyguardStyleWidgetUIControllerImpl")
                        .getDeclaredMethod("setKeyguardStyleWidgetAlpha", float.class));
                Class<?> controller = type("com.oplus.systemui.keyguard.clockstyle.KeyguardStyleClockControllerImpl");
                module.deoptimize(controller.getDeclaredMethod("access$setKeyguardStyleClockScale",
                        controller, float.class, float.class, float.class));
            }
        });
    }
    private Class<?> type(String name) throws ClassNotFoundException { return Class.forName(name, false, loader); }
    private void installMethod(String owner, String name, int parameterCount, Hooker hooker) throws ReflectiveOperationException {
        Method found = null;
        for (Method candidate : type(owner).getDeclaredMethods()) {
            if (candidate.getName().equals(name) && candidate.getParameterCount() == parameterCount) {
                if (found != null) throw new NoSuchMethodException("Ambiguous: " + owner + "." + name);
                found = candidate;
            }
        }
        if (found == null) throw new NoSuchMethodException(owner + "." + name);
        found.setAccessible(true);
        module.hook(found).intercept(hooker);
    }
    private interface Install { void run() throws Throwable; }
    private void independent(String feature, Install action) {
        try { action.run(); module.info("已加载 " + feature); }
        catch (Throwable error) { module.warnOnce("未完整加载 " + feature, error); }
    }
}
