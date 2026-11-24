package dev.lsposed.fluidcloud;

import android.content.Context;
import android.graphics.Outline;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.GradientDrawable;
import android.view.View;
import android.graphics.drawable.ShapeDrawable;
import android.graphics.drawable.shapes.PathShape;
import androidx.core.view.OneShotPreDrawListener;
import android.widget.FrameLayout;
import io.github.libxposed.api.XposedInterface;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.function.Supplier;

/** Card chrome on both plugin generations. The OEM owns geometry, materials and timing. */
final class NativeCardCorners {
    private final Supplier<CloudCorners.Style> currentStyle;
    private final Path path = new Path();
    private final RectF bounds = new RectF();
    private Class<?> instantType;

    NativeCardCorners(Supplier<CloudCorners.Style> currentStyle) { this.currentStyle = currentStyle; }

    void install(HookEntry module, ClassLoader loader, NativeCameraCard cards, View pluginRoot,
                 ArrayList<XposedInterface.HookHandle> hooks) throws ReflectiveOperationException {
        instantType = Reflect.type(loader, NativeCameraCard.VIEWS + "InstantContainer");
        if (cards.legacy) installLegacyBlur(module, loader, cards.alternateLegacy, hooks);
        else installCornerConfig(module, loader, cards, pluginRoot, hooks);
    }

    private void installCornerConfig(HookEntry module, ClassLoader loader, NativeCameraCard cards, View pluginRoot,
                                     ArrayList<XposedInterface.HookHandle> hooks) throws ReflectiveOperationException {
        // ColorOS 17 uses this same tuple for blur, material tint, pressed glow,
        // steady outlines and animated masks. The notification path has its own factory.
        Class<?> tuple = Reflect.type(loader, "kotlin.Triple");
        Class<?> util = Reflect.type(loader, "com.oplus.systemui.plugins.seedling.util.c");
        Method factory = Reflect.method(util, "f", tuple);
        var constructor = tuple.getDeclaredConstructor(Object.class, Object.class, Object.class);
        constructor.setAccessible(true);
        Class<?> manager = Reflect.type(loader, "com.oplus.view.ViewRootManager");
        Class<?> background = Reflect.type(loader, "com.heytap.log.nx.obus.a");
        Class<?> spotlight = PluginClasses.of(cards.cardType).unique(NativeCameraCard.VIEWS, type ->
                PluginClasses.has(type,void.class,android.graphics.Canvas.class,int.class,int.class)
                        && PluginClasses.has(type,void.class,FrameLayout.class));
        for (Method caller : background.getDeclaredMethods())
            if(caller.getReturnType()==void.class && caller.getParameterCount()>=2
                    && caller.getParameterTypes()[0]==View.class && caller.getParameterTypes()[1]==manager)
                module.deoptimize(caller);
        for (Method caller : new Method[]{
                Reflect.method(cards.cardType,"g",void.class),Reflect.method(cards.cardType,"k",void.class),
                Reflect.method(instantType,"g",void.class)}) module.deoptimize(caller);
        for (var caller : spotlight.getDeclaredConstructors()) module.deoptimize(caller);
        Class<?> fragment = Reflect.type(loader, "com.oplus.systemui.plugins.seedling.card.animation.x");
        module.deoptimize(Reflect.uniqueMethod(fragment,method->method.getReturnType()==void.class
                && method.getParameterCount()==3 && method.getParameterTypes()[1]==float.class
                && method.getParameterTypes()[0]==method.getParameterTypes()[2]));
        hooks.add(module.hook(factory).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
            CloudCorners.Style style = currentStyle.get();
            if (!style.enabled()) return chain.proceed();
            float radius = style.dp() * pluginRoot.getResources().getDisplayMetrics().density;
            // Native mode 2 uses the G2 smooth outline adapter and material.
            return constructor.newInstance(style.continuous() ? 2 : 0, radius,
                    style.continuous() ? 3f : 0f);
        }));
        // Card views can be recycled. Their backgrounds and outlines are rebuilt
        // when reopened, but the native pressed-glow helper retains its old tuple.
        var glowProfile = Reflect.uniqueField(spotlight,field->Reflect.instance(field)&&field.getType()==tuple);
        Method bindGlow = Reflect.signature(spotlight, void.class, FrameLayout.class);
        module.deoptimize(Reflect.method(cards.cardType, "onAttachedToWindow", void.class));
        module.deoptimize(Reflect.method(instantType, "onAttachedToWindow", void.class));
        module.deoptimize(Reflect.method(spotlight, "b", void.class, android.graphics.Canvas.class, int.class, int.class));
        hooks.add(module.hook(bindGlow).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
            Object glow = chain.getThisObject();
            Object profile = Reflect.call(factory, null);
            if (!java.util.Objects.equals(Reflect.get(glowProfile, glow), profile))
                glowProfile.set(glow, profile);
            return chain.proceed();
        }));
    }

    void installMenuPreview(HookEntry module, ClassLoader hostLoader, Class<?> cardType,
                            ArrayList<XposedInterface.HookHandle> hooks) throws ReflectiveOperationException {
        String menu = "com.oplus.systemui.statusbar.notification.customcard.popupmenu.";
        Class<?> cloner = Reflect.type(hostLoader, menu + "OplusCustomRowCloner");
        Class<?> snapshot = Reflect.type(hostLoader, menu + "CloneContentView");
        NativeTemplateStroke.PathSource material = null;
        try { material = NativeTemplateStroke.materialPath(cardType.getClassLoader()); }
        catch (ReflectiveOperationException | LinkageError unavailable) { /* Legacy card contour below. */ }
        final NativeTemplateStroke.PathSource nativeMaterial = material;
        Method capture = Reflect.method(cloner, "cloneContent", void.class, View.class, snapshot, String.class);
        hooks.add(module.hook(capture).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
            Object result = chain.proceed();
            CloudCorners.Style style = currentStyle.get();
            if (!style.enabled() || !cardType.isInstance(chain.getArg(0))) return result;
            View original = (View) chain.getArg(0);
            View preview = (View) chain.getArg(1);
            if (original.getWidth() == 0 || original.getHeight() == 0) return result;
            int width = original.getWidth(), height = original.getHeight();
            Path contour = new Path();
            if (nativeMaterial != null) try {
                nativeMaterial.build(contour, new Rect(0, 0, width, height));
            } catch (RuntimeException | LinkageError unavailable) { contour.rewind(); }
            if (contour.isEmpty()) {
                CloudCorners.path(contour, new RectF(0, 0, width, height),
                        style.requested(original.getResources().getDisplayMetrics().density), style.continuous());
            }
            // The foreground is already a faithful snapshot, including its antialiased rim.
            // Only the solid notification backdrop, installed on attach, needs the material contour.
            OneShotPreDrawListener.add(preview, () -> {
                if (preview.getBackground() instanceof GradientDrawable background && background.getColor() != null
                        && background.getShape() == GradientDrawable.RECTANGLE && background.getColors() == null) {
                    ShapeDrawable backdrop = new ShapeDrawable(new PathShape(contour, width, height));
                    backdrop.setTintList(background.getColor());
                    backdrop.setAlpha(background.getAlpha());
                    preview.setBackground(backdrop);
                }
            });
            return result;
        }));
    }

    private void installLegacyBlur(HookEntry module, ClassLoader loader, boolean alternate,
                                   ArrayList<XposedInterface.HookHandle> hooks) throws ReflectiveOperationException {
        Class<?> manager = Reflect.type(loader, "com.oplus.view.ViewRootManager");
        Class<?> background = Reflect.type(loader, NativeCameraCard.VIEWS + "CardBackgroundView");
        Method radius = Reflect.method(manager, "setCornerRadius", void.class, float.class);
        Method setup = Reflect.method(Reflect.type(loader, alternate ? "t5.e" : "j6.d"), "b", void.class,
                Context.class, View.class, manager);
        module.deoptimize(Reflect.method(background,"onAttachedToWindow",void.class));
        module.deoptimize(Reflect.method(instantType,"s",void.class));
        hooks.add(module.hook(setup).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
            Object result = chain.proceed();
            View view = (View) chain.getArg(1);
            CloudCorners.Style style = currentStyle.get();
            if (style.enabled() && (background.isInstance(view) || instant(view))) {
                Object blur = chain.getArg(2);
                float pixels = style.dp() * view.getResources().getDisplayMetrics().density;
                if (blur != null) Reflect.call(radius, blur, pixels);
                if (view.getBackground() instanceof GradientDrawable shape) {
                    shape.mutate();
                    shape.setCornerRadius(pixels);
                }
                view.invalidateOutline();
            }
            return result;
        }));
    }

    private boolean instant(View view) { return view != null && instantType != null && instantType.isInstance(view.getParent()); }

    void legacyOutline(NativeCameraCard cards, View view, Outline outline, Object provider) {
        CloudCorners.Style style = currentStyle.get();
        if (!style.enabled() || outline == null || view == null || outline.isEmpty()) return;
        if (!cards.cardType.isInstance(view) && !instant(view)
                && !(view.getParent() instanceof View parent && cards.cardType.isInstance(parent))) return;
        var mask = cards.outlineBounds(provider);
        if (mask == null) bounds.set(0,0,view.getWidth(),view.getHeight());
        else bounds.set(mask);
        float scale = mask == null ? 1 : Math.max(view.getScaleX(), view.getScaleY());
        if (!Float.isFinite(scale) || scale <= 0) scale = 1;
        float radius = style.radius(view.getResources().getDisplayMetrics().density / scale,bounds.width(),bounds.height());
        if (style.continuous() && radius > 0) {
            CloudCorners.path(path,bounds,radius,true);
            if (!path.isEmpty()) outline.setPath(path);
        } else outline.setRoundRect((int)bounds.left,(int)bounds.top,(int)bounds.right,(int)bounds.bottom,radius);
    }
}
