package dev.lsposed.fluidcloud;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.view.View;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.WeakHashMap;
import java.util.function.Predicate;
import java.util.function.Supplier;

/** The shared page's stock foreground paints its stroke using ColorOS GradientDrawable geometry. */
final class NativeTemplateStroke {
    private record Shape(float radius, int type, float weight, boolean smooth) { }
    private record Owned(Shape stock, Shape applied) { }
    private final Field state, type, weight, extension;
    private final Method smooth, configure;
    private final int continuousType;
    private final WeakHashMap<GradientDrawable, Owned> shapes = new WeakHashMap<>();

    @FunctionalInterface
    interface PathSource { void build(Path path, Rect bounds); }

    static PathSource materialPath(ClassLoader loader) throws ReflectiveOperationException {
        Class<?> tuple = Reflect.type(loader, "kotlin.Triple");
        var factory = Reflect.method(Reflect.type(loader, "com.oplus.systemui.plugins.seedling.util.c"), "f", tuple);
        var draw = PluginClasses.materialPath(name -> Reflect.type(loader, name), tuple);
        return (path, bounds) -> Reflect.call(draw, null, path, bounds.left, bounds.top, bounds.right, bounds.bottom,
                Reflect.call(factory, null));
    }

    static final class Contour {
        private final Field strokePaint;
        private final PathSource source;

        Contour(PathSource source) throws NoSuchFieldException {
            this.source = source;
            strokePaint = Reflect.field(GradientDrawable.class, "mStrokePaint", Paint.class);
        }

        GradientDrawable wrap(GradientDrawable stock, View card, Supplier<CloudCorners.Style> style,
                              Predicate<View> extended) {
            // Native shared-template foregrounds use a transparent shape plus a stroke.
            if (stock instanceof Aligned || stock.getShape() != GradientDrawable.RECTANGLE
                    || stock.getColors() != null || stock.getColor() == null
                    || stock.getColor().getDefaultColor() != android.graphics.Color.TRANSPARENT) return stock;
            return new Aligned(stock, card, style, extended);
        }

        private final class Aligned extends GradientDrawable implements Drawable.Callback {
            private final GradientDrawable stock;
            private final WeakReference<View> card;
            private final Supplier<CloudCorners.Style> style;
            private final Predicate<View> extended;
            private final Rect rect = new Rect();
            private final Path path = new Path();
            private final Paint paint = new Paint();
            private CloudCorners.Style lastStyle;
            private int width, height, density;

            Aligned(GradientDrawable stock, View card, Supplier<CloudCorners.Style> style,
                    Predicate<View> extended) {
                this.stock = stock;
                this.card = new WeakReference<>(card);
                this.style = style;
                this.extended = extended;
            }

            @Override public void draw(Canvas canvas) {
                View owner = card.get();
                Rect bounds = getBounds();
                CloudCorners.Style current = style.get();
                if (owner == null || bounds.isEmpty() || (!current.enabled() && !extended.test(owner))
                        || bounds.width() != owner.getWidth() || bounds.height() != owner.getHeight()) {
                    stock.draw(canvas);
                    return;
                }
                Paint nativePaint = (Paint) Reflect.get(strokePaint, stock);
                if (nativePaint == null || nativePaint.getStrokeWidth() <= 0f || nativePaint.getAlpha() == 0) return;
                if (!updatePath(owner, bounds, current)) {
                    stock.draw(canvas);
                    return;
                }
                paint.set(nativePaint);
                int alpha = stock.getAlpha();
                paint.setAlpha(nativePaint.getAlpha() * (alpha + (alpha >>> 7)) >>> 8);
                paint.setColorFilter(stock.getColorFilter());
                paint.setStrokeWidth(nativePaint.getStrokeWidth() * 2f);
                int save = canvas.save();
                try {
                    canvas.translate(bounds.left, bounds.top);
                    canvas.clipPath(path);
                    canvas.drawPath(path, paint);
                } finally { canvas.restoreToCount(save); }
            }

            private boolean updatePath(View owner, Rect bounds, CloudCorners.Style current) {
                int dpi = owner.getResources().getDisplayMetrics().densityDpi;
                if (width == bounds.width() && height == bounds.height() && density == dpi && current.equals(lastStyle))
                    return !path.isEmpty();
                width = bounds.width();
                height = bounds.height();
                density = dpi;
                lastStyle = current;
                path.rewind();
                try {
                    rect.set(0, 0, width, height);
                    source.build(path, rect);
                } catch (RuntimeException | LinkageError unavailable) {
                    path.rewind();
                }
                return !path.isEmpty();
            }

            @Override public void setStroke(int width, int color) { stock.setStroke(width, color); invalidateSelf(); }
            @Override public void setAlpha(int alpha) { stock.setAlpha(alpha); invalidateSelf(); }
            @Override public int getAlpha() { return stock.getAlpha(); }
            @Override public void setColorFilter(ColorFilter filter) { stock.setColorFilter(filter); invalidateSelf(); }
            @Override public boolean isStateful() { return stock.isStateful(); }
            @Override protected boolean onStateChange(int[] state) { return stock.setState(state); }
            @Override protected void onBoundsChange(Rect bounds) { stock.setBounds(bounds); }
            @Override public void invalidateDrawable(Drawable who) { invalidateSelf(); }
            @Override public void scheduleDrawable(Drawable who, Runnable what, long when) { scheduleSelf(what, when); }
            @Override public void unscheduleDrawable(Drawable who, Runnable what) { unscheduleSelf(what); }
        }
    }

    static GradientDrawable original(GradientDrawable drawable) {
        return drawable instanceof Contour.Aligned aligned ? aligned.stock : drawable;
    }

    static void bind(GradientDrawable drawable) {
        if (drawable instanceof Contour.Aligned aligned) aligned.stock.setCallback(aligned);
    }

    NativeTemplateStroke(ClassLoader loader) throws ReflectiveOperationException {
        Class<?> drawableState = Reflect.type(loader, "android.graphics.drawable.GradientDrawable$GradientState");
        Class<?> ext = Reflect.type(loader, "android.graphics.drawable.IGradientDrawableExt");
        Class<?> rounded = Reflect.type(loader, "com.oplus.view.OplusSmoothRoundedManager");
        state = Reflect.field(GradientDrawable.class, "mGradientState", drawableState);
        extension = Reflect.field(GradientDrawable.class, "mGradientDrawableExt", ext);
        type = Reflect.field(drawableState, "mSmoothType", int.class);
        weight = Reflect.field(drawableState, "mSmoothWeight", float.class);
        smooth = Reflect.method(ext, "getSmoothRoundStyle", boolean.class);
        configure = Reflect.method(ext, "setSmoothRoundStyle", void.class, boolean.class, float.class, int.class);
        continuousType = (int) Reflect.call(Reflect.method(rounded, "getG2CornerType", int.class), null);
    }

    void apply(GradientDrawable drawable, CloudCorners.Style style, float density) throws IllegalAccessException {
        if (drawable.getCornerRadii() != null || drawable.getShape() != GradientDrawable.RECTANGLE) return;
        Owned previous = shapes.get(drawable);
        if (!style.enabled() && previous == null) return;
        Shape current = read(drawable);
        if (!style.enabled()) {
            shapes.remove(drawable);
            if (current.equals(previous.applied())) write(drawable, previous.stock());
            return;
        }
        boolean continuous = style.continuous();
        Shape target = new Shape(style.dp() * density, continuous ? continuousType : -1, continuous ? 3f : 0f, continuous);
        if (target.equals(current)) return;
        Shape stock = previous != null && current.equals(previous.applied()) ? previous.stock() : current;
        write(drawable, target);
        shapes.put(drawable, new Owned(stock, target));
    }

    private Shape read(GradientDrawable drawable) throws IllegalAccessException {
        Object nativeState = Reflect.get(state, drawable);
        return new Shape(drawable.getCornerRadius(), type.getInt(nativeState), weight.getFloat(nativeState),
                (boolean) Reflect.call(smooth, Reflect.get(extension, drawable)));
    }

    private void write(GradientDrawable drawable, Shape value) throws IllegalAccessException {
        Object nativeState = Reflect.get(state, drawable);
        type.setInt(nativeState, value.type());
        weight.setFloat(nativeState, value.weight());
        Reflect.call(configure, Reflect.get(extension, drawable), value.smooth(), value.weight(), value.type());
        drawable.setCornerRadius(value.radius());
    }

    void restore() throws IllegalAccessException {
        for (GradientDrawable drawable : new ArrayList<>(shapes.keySet()))
            if (drawable != null) apply(drawable, CloudCorners.Style.OEM, 1f);
    }
}
