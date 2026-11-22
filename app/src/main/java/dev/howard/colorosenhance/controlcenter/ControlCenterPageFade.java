package dev.howard.colorosenhance.controlcenter;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.view.ViewGroup;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.IntSupplier;

/** Draws the clipped portion of moving tiles in the narrow gutter outside the native page. */
final class ControlCenterPageFade implements AutoCloseable {
    record Target(ViewGroup view, IntSupplier offset) {}

    interface TileDraw {
        void draw(ViewGroup pager, Canvas canvas, View tile);
    }

    private final Map<ViewGroup, Page> pages = new IdentityHashMap<>();
    private final TileDraw drawTile;
    private final Consumer<Throwable> failed;

    ControlCenterPageFade(TileDraw drawTile, Consumer<Throwable> failed) {
        this.drawTile = drawTile;
        this.failed = failed;
    }

    void sync(List<Target> targets) {
        var wanted = Collections.newSetFromMap(new IdentityHashMap<ViewGroup, Boolean>());
        for (Target target : targets) {
            ViewGroup view = target.view();
            if (view.getWidth() <= 0 || view.getHeight() <= 0 || view.getVisibility() != View.VISIBLE
                    || !(view.getParent() instanceof ViewGroup parent)) continue;
            wanted.add(view);
            Page page = pages.get(view);
            if (page == null || page.parent != parent) {
                if (page != null) page.close();
                page = new Page(view, parent);
                pages.put(view, page);
                parent.getOverlay().add(page);
            }
            page.offset = target.offset();
            page.setBounds(0, 0, parent.getWidth(), parent.getHeight());
            page.pagerDrawn();
        }
        pages.entrySet().removeIf(entry -> {
            if (wanted.contains(entry.getKey())) return false;
            entry.getValue().close();
            return true;
        });
    }

    boolean contains(View view) { return pages.containsKey(view); }

    void pagerDrawn(View view) {
        Page page = pages.get(view);
        if (page != null) page.pagerDrawn();
    }

    @Override public void close() {
        for (Page page : pages.values()) page.close();
        pages.clear();
    }

    private static float motionDistance(int offset, int width) {
        if (width <= 0) return 0;
        int within = Math.floorMod(offset, width);
        return Math.min(within, width - within);
    }

    private final class Page extends Drawable {
        private final ViewGroup view, parent;
        private final Paint mask = new Paint();
        private final Matrix gradientMatrix = new Matrix();
        private final LinearGradient leftGradient = new LinearGradient(0, 0, 1, 0,
                Color.TRANSPARENT, Color.BLACK, Shader.TileMode.CLAMP);
        private final LinearGradient rightGradient = new LinearGradient(0, 0, 1, 0,
                Color.BLACK, Color.TRANSPARENT, Shader.TileMode.CLAMP);
        private final RectF pageBounds = new RectF(), tileBounds = new RectF(), siblingBounds = new RectF();
        private IntSupplier offset;
        private int drawnOffset;
        private float distance;
        private boolean unavailable;

        Page(ViewGroup view, ViewGroup parent) {
            this.view = view;
            this.parent = parent;
            mask.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_IN));
        }

        void pagerDrawn() {
            if (unavailable) return;
            int nextOffset = offset.getAsInt();
            float nextDistance = motionDistance(nextOffset, view.getMeasuredWidth());
            if (nextDistance != distance || nextDistance > 0 && nextOffset != drawnOffset) invalidateSelf();
            drawnOffset = nextOffset;
            distance = nextDistance;
        }

        @Override public void draw(Canvas canvas) {
            if (unavailable || distance <= 0 || view.getParent() != parent
                    || view.getVisibility() != View.VISIBLE || view.getAlpha() <= 0) return;
            try {
                drawEdges(canvas);
            } catch (RuntimeException | LinkageError error) {
                unavailable = true;
                failed.accept(error);
            }
        }

        private void drawEdges(Canvas canvas) {
            boolean clipPadding = view.getClipToPadding() && (view.getPaddingLeft() != 0 || view.getPaddingRight() != 0
                    || view.getPaddingTop() != 0 || view.getPaddingBottom() != 0);
            // An unclipped stock pager already draws its overflow in the native pass.
            if ((!clipPadding && !parent.getClipChildren()) || view.getClipBounds() != null) return;
            float start = clipPadding ? view.getPaddingLeft() : 0;
            float end = view.getWidth() - (clipPadding ? view.getPaddingRight() : 0);
            float top = clipPadding ? view.getPaddingTop() : 0;
            float bottom = view.getHeight() - (clipPadding ? view.getPaddingBottom() : 0);
            if (start >= end || top >= bottom || view.getScaleX() <= 0) return;

            pageBounds.set(start, top, end, bottom);
            view.getMatrix().mapRect(pageBounds);
            pageBounds.offset(view.getLeft(), view.getTop());
            float low = parent.getClipToPadding() ? parent.getPaddingLeft() : 0;
            float high = parent.getWidth() - (parent.getClipToPadding() ? parent.getPaddingRight() : 0);
            for (int i = 0; i < parent.getChildCount(); i++) {
                View sibling = parent.getChildAt(i);
                if (sibling == view || sibling.getVisibility() != View.VISIBLE) continue;
                siblingBounds.set(0, 0, sibling.getWidth(), sibling.getHeight());
                sibling.getMatrix().mapRect(siblingBounds);
                siblingBounds.offset(sibling.getLeft(), sibling.getTop());
                if (siblingBounds.bottom <= pageBounds.top || siblingBounds.top >= pageBounds.bottom) continue;
                if (siblingBounds.right <= pageBounds.left) low = Math.max(low, siblingBounds.right);
                if (siblingBounds.left >= pageBounds.right) high = Math.min(high, siblingBounds.left);
            }
            float limit = Math.min(distance, 20 * view.getResources().getDisplayMetrics().density);
            float left = Math.min(limit, Math.max(0, pageBounds.left - low) / view.getScaleX());
            float right = Math.min(limit, Math.max(0, high - pageBounds.right) / view.getScaleX());
            if (left <= 0 && right <= 0) return;

            int save = canvas.save();
            try {
                canvas.translate(view.getLeft(), view.getTop());
                canvas.concat(view.getMatrix());
                canvas.translate(-view.getScrollX(), -view.getScrollY());
                start += view.getScrollX();
                end += view.getScrollX();
                top += view.getScrollY();
                bottom += view.getScrollY();
                mask.setAlpha(Math.round(view.getAlpha() * view.getTransitionAlpha() * 255));
                if (left > 0) drawStrip(canvas, start - left, top, start, bottom, leftGradient);
                if (right > 0) drawStrip(canvas, end, top, end + right, bottom, rightGradient);
            } finally {
                canvas.restoreToCount(save);
            }
        }

        private void drawStrip(Canvas canvas, float left, float top, float right, float bottom,
                               LinearGradient gradient) {
            int save = 0;
            try {
                for (int i = 0; i < view.getChildCount(); i++) {
                    View child = view.getChildAt(i);
                    if (child.getVisibility() != View.VISIBLE || child.getAlpha() <= 0) continue;
                    tileBounds.set(0, 0, child.getWidth(), child.getHeight());
                    child.getMatrix().mapRect(tileBounds);
                    tileBounds.offset(child.getLeft(), child.getTop());
                    if (!tileBounds.intersects(left, top, right, bottom)) continue;
                    if (save == 0) {
                        save = canvas.saveLayer(left, top, right, bottom, null);
                        canvas.clipRect(left, top, right, bottom);
                    }
                    drawTile.draw(view, canvas, child);
                }
                if (save != 0) {
                    gradientMatrix.setScale(right - left, 1);
                    gradientMatrix.postTranslate(left, 0);
                    gradient.setLocalMatrix(gradientMatrix);
                    mask.setShader(gradient);
                    canvas.drawRect(left, top, right, bottom, mask);
                }
            } finally {
                if (save != 0) canvas.restoreToCount(save);
            }
        }

        void close() { parent.getOverlay().remove(this); }

        @Override public void setAlpha(int alpha) {}
        @Override public void setColorFilter(ColorFilter colorFilter) {}
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }
}
