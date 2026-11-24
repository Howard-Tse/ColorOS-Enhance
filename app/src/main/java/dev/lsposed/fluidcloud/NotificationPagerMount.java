package dev.lsposed.fluidcloud;

import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import java.util.List;

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
}
