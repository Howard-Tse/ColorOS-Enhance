package dev.lsposed.fluidcloud;

import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.GradientDrawable;

/** The capsule fill and native contrast stroke use the same contour on both plugin generations. */
final class CapsuleChromeDrawable extends GradientDrawable {
    private final Path shape = new Path(), innerShape = new Path(), strokeShape = new Path();
    private final RectF bounds = new RectF();
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final boolean continuous;
    private final float radius;
    private CloudCorners.Axes animated;
    private ColorStateList stroke;
    private int strokeWidth;
    private boolean dirty = true;

    CapsuleChromeDrawable(GradientDrawable source, float radius, boolean continuous) {
        this.radius = radius;
        this.continuous = continuous;
        setColor(source.getColor());
        setAlpha(source.getAlpha());
        setLevel(source.getLevel());
        setState(source.getState());
    }

    CapsuleChromeDrawable copy() { return new CapsuleChromeDrawable(this, radius, continuous); }

    void syncStroke(CapsuleChromeDrawable source) { setStroke(source.strokeWidth, source.stroke); }

    void animatedCorners(CloudCorners.Axes corners) {
        if (java.util.Objects.equals(animated, corners)) return;
        animated = corners;
        dirty = true;
        invalidateSelf();
    }

    @Override public void setStroke(int width, int color) { setStroke(width, ColorStateList.valueOf(color)); }

    @Override public void setStroke(int width, ColorStateList color) {
        if (width == strokeWidth && java.util.Objects.equals(stroke, color)) return;
        strokeWidth = width;
        stroke = color;
        dirty = true;
        invalidateSelf();
    }

    @Override public boolean isStateful() { return super.isStateful() || stroke != null && stroke.isStateful(); }

    @Override protected boolean onStateChange(int[] state) {
        return super.onStateChange(state) || stroke != null && stroke.isStateful();
    }

    @Override protected void onBoundsChange(Rect value) {
        super.onBoundsChange(value);
        dirty = true;
    }

    @Override public void draw(Canvas canvas) {
        updatePaths();
        if (setPaint(getColor())) canvas.drawPath(shape, paint);
        if (strokeWidth > 0 && setPaint(stroke)) canvas.drawPath(strokeShape, paint);
    }

    @Override public void getOutline(Outline outline) {
        updatePaths();
        if (shape.isEmpty()) outline.setEmpty();
        else outline.setPath(shape);
        outline.setAlpha(alpha(getColor()) / 255f);
    }

    private boolean setPaint(ColorStateList colors) {
        int alpha = alpha(colors);
        if (alpha == 0) return false;
        paint.setColor(colors.getColorForState(getState(), colors.getDefaultColor()));
        paint.setAlpha(alpha);
        paint.setColorFilter(getColorFilter());
        return true;
    }

    private int alpha(ColorStateList colors) {
        return colors == null ? 0 : Math.round(Color.alpha(colors.getColorForState(getState(), colors.getDefaultColor()))
                * getAlpha() / 255f);
    }

    private void updatePaths() {
        if (!dirty) return;
        dirty = false;
        bounds.set(getBounds());
        var axes = animated == null ? CloudCorners.Axes.capsule(radius, bounds.width(), bounds.height()) : animated;
        CloudCorners.path(shape, bounds, axes, continuous);
        strokeShape.rewind();
        if (strokeWidth > 0) {
            float inset = strokeWidth;
            bounds.inset(inset, inset);
            CloudCorners.path(innerShape, bounds, axes.inset(inset), continuous);
            strokeShape.setFillType(Path.FillType.EVEN_ODD);
            strokeShape.addPath(shape);
            strokeShape.addPath(innerShape);
        }
    }
}
