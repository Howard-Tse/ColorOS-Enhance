package dev.lsposed.fluidcloud;

import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import java.lang.ref.WeakReference;
import java.util.List;
import java.util.Set;
import java.util.WeakHashMap;

/** Keep the live pager outside the native template's replaceable children. The native
 * row still measures and clips using its own content view and translates both on a swipe. */
final class NotificationPagerMount {
    private final FrameLayout row;
    private final View original, pages;
    private final List<View> translated;
    private final int originalHeight, originalVisibility;
    private boolean showing;

    NotificationPagerMount(FrameLayout row, View original, View pages, List<View> translated) {
        this.row = row;
        this.original = original;
        this.pages = pages;
        this.translated = translated;
        FrameLayout.LayoutParams source = (FrameLayout.LayoutParams) original.getLayoutParams();
        originalHeight = source.height;
        originalVisibility = original.getVisibility();
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(source);
        params.height = original.getHeight();
        pages.setVisibility(View.INVISIBLE);
        pages.setTranslationX(original.getTranslationX());
        row.addView(pages, row.indexOfChild(original) + 1, params);
        if (!translated.contains(pages)) translated.add(pages);
    }

    void present(int height) {
        if (height <= 0 || original.getParent() != row || pages.getParent() != row) return;
        setHeight(original, height);
        setHeight(pages, height);
        if (!showing) {
            showing = true;
            original.setVisibility(View.INVISIBLE);
            pages.setVisibility(originalVisibility);
        }
    }

    void remove() {
        translated.remove(pages);
        if (showing) {
            setHeight(original, originalHeight);
            original.setVisibility(originalVisibility);
        }
        if (pages.getParent() == row) row.removeView(pages);
    }

    private static void setHeight(View view, int height) {
        ViewGroup.LayoutParams params = view.getLayoutParams();
        if (params != null && params.height != height) {
            params.height = height;
            view.setLayoutParams(params);
        }
    }

    static boolean settled(float position, int selected, int scrollState) {
        return scrollState == 0 && position == selected;
    }

    static int between(int first, int second, float fraction) {
        if (first <= 0 || (fraction > 0f && second <= 0)) return 0;
        return fraction == 0f ? first : Math.round(first + (second - first) * fraction);
    }

    /** Keeps the camera geometry and native material tied to the row during page replacement. */
    static final class Expanded {
        private static final WeakHashMap<View, WeakReference<Expanded>> rows = new WeakHashMap<>();
        private final View row, original;
        private ViewGroup shell;
        private float bodyOffset, appliedBodyY;
        private final WeakHashMap<View, Float> backgrounds = new WeakHashMap<>();
        private final WeakHashMap<GradientDrawable, Integer> strokes = new WeakHashMap<>();

        Expanded(View row, View original) {
            this.row = row;
            this.original = original;
            rows.put(row, new WeakReference<>(this));
        }

        static Expanded find(View row, View body) {
            var ref = rows.get(row);
            Expanded placement = ref == null ? null : ref.get();
            return placement != null && placement.original == body ? placement : null;
        }

        void mount(ViewGroup pages) { shell = pages; }
        boolean owns(View view) { return shell == view; }
        boolean showing() { return shell != null && shell.getVisibility() == View.VISIBLE; }
        void matchBody() {
            if (shell == null) return;
            shell.setTranslationX(original.getTranslationX() + original.getLeft() - shell.getLeft());
            shell.setTranslationY(original.getTranslationY() + original.getTop() - shell.getTop());
            shell.setScaleX(original.getScaleX());
            shell.setScaleY(original.getScaleY());
            shell.setAlpha(original.getAlpha());
        }

        void keepCameraGap(int inset, Rect outline) {
            float nativeY = original.getTranslationY();
            if (nativeY == appliedBodyY) nativeY -= bodyOffset;
            bodyOffset = outline == null ? 0f : cameraOffset(inset, outline.top, row.getScaleY(),
                    original.getTop() + nativeY);
            appliedBodyY = nativeY + bodyOffset;
            if (original.getTranslationY() != appliedBodyY) original.setTranslationY(appliedBodyY);
            matchBody();
        }

        static float cameraOffset(int inset, int outlineTop, float scaleY, float nativeContentTop) {
            if (inset <= 0 || !Float.isFinite(scaleY) || scaleY <= .01f) return 0f;
            // The outline's visual top stays pinned to the camera during the morph.
            // Keep its header clear in screen pixels as the local clip and scale change.
            return Math.max(0f, outlineTop + inset / scaleY - nativeContentTop);
        }

        void useSharedMaterial(Class<?> backgroundType, Set<View> strokeHosts) {
            if (shell != null) hidePageMaterial(shell, backgroundType, strokeHosts);
        }

        private void hidePageMaterial(View node, Class<?> backgroundType, Set<View> strokeHosts) {
            if (backgroundType != null && backgroundType.isInstance(node)) {
                if (node.getAlpha() > 0f) {
                    backgrounds.put(node, node.getAlpha());
                    node.setAlpha(0f);
                }
                return;
            }
            if (strokeHosts.contains(node) && node.getForeground() instanceof GradientDrawable stroke
                    && stroke.getAlpha() > 0) {
                strokes.put(stroke, stroke.getAlpha());
                stroke.setAlpha(0);
            }
            if (node instanceof ViewGroup group)
                for (int i = 0; i < group.getChildCount(); i++) hidePageMaterial(group.getChildAt(i), backgroundType, strokeHosts);
        }

        void restoreMaterial() {
            backgrounds.forEach((view, alpha) -> { if (view.getAlpha() == 0f) view.setAlpha(alpha); });
            strokes.forEach((stroke, alpha) -> { if (stroke.getAlpha() == 0) stroke.setAlpha(alpha); });
            backgrounds.clear();
            strokes.clear();
        }

        void close() {
            keepCameraGap(0, null);
            restoreMaterial();
            shell = null;
            if (find(row, original) == this) rows.remove(row);
            row.invalidate();
        }
    }
}
