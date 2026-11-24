package dev.lsposed.fluidcloud;

import android.content.res.Resources;
import android.graphics.Rect;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import io.github.libxposed.api.XposedInterface;
import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.WeakHashMap;

/** Adjusts stock layout inputs before measurement. The camera inset and material layers have separate owners. */
final class NativeCardSpacing implements View.OnAttachStateChangeListener {
    private CardSpacing options = CardSpacing.OEM;
    private final WeakHashMap<View, Padding> padding = new WeakHashMap<>();
    private final WeakHashMap<View, Width> widths = new WeakHashMap<>();
    private final WeakHashMap<ViewGroup, Integer> listGaps = new WeakHashMap<>();
    private final WeakHashMap<Resources, TemplateIds> ids = new WeakHashMap<>();
    private final Set<View> observed = Collections.newSetFromMap(new WeakHashMap<>());
    private Method invalidateDecorations;
    private final Set<ViewGroup> standaloneInstants = Collections.newSetFromMap(new WeakHashMap<>());
    private Method instantView, instantExpanded;

    private record Width(WeakReference<ViewGroup.LayoutParams> params, int stock, int applied) { }
    private record Padding(Rect stock, Rect applied, boolean relative) { }
    private record TemplateIds(int rows, int header, int album, int icon, int pager) {
        TemplateIds(Resources r) {
            this(id(r, "section_root_container"), id(r, "header_container"), id(r, "img_album_art"),
                    id(r, "img_top_right_icon"), id(r, "music_viewpager"));
        }
        private static int id(Resources r, String name) {
            // These resource IDs belong to the installed plugin and vary between firmware builds.
            return r.getIdentifier(name, "id", "com.oplus.systemui.plugins");
        }
    }

    boolean hasPadding() { return options.hasPadding(); }
    boolean adjustsCard() { return options.side() != 0 || hasPadding(); }
    boolean configure(CardSpacing next) {
        if (options.equals(next)) return false;
        restoreViews();
        options = next;
        if (adjustsCard()) for (ViewGroup host : new ArrayList<>(standaloneInstants))
            if (host != null && host.isAttachedToWindow()) applyStandaloneInstant(host);
        return true;
    }

    // Older instant hosts can inherit dispatchDraw. Their large-card layout remains
    // usable even when the camera/material adapter cannot be installed.
    void installStandaloneInstant(HookEntry module, NativeCameraCard cards, List<XposedInterface.HookHandle> hooks)
            throws ReflectiveOperationException {
        ClassLoader loader = cards.containerType.getClassLoader();
        Class<?> host = Reflect.type(loader, NativeCameraCard.VIEWS + "InstantContainer");
        Class<?> model = Reflect.type(loader, cards.alternateLegacy ? "t4.n" : cards.legacy ? "h5.n"
                : "com.oplus.systemui.plugins.seedling.card.ui.model.n");
        Method state = Reflect.method(host, "k", void.class, model, boolean.class, boolean.class);
        Method position = Reflect.method(host, cards.legacy ? "q" : "s", void.class);
        instantView = Reflect.method(host, "getInstantView", View.class);
        instantExpanded = Reflect.method(host, "l", boolean.class);
        for (Method change : List.of(state, position))
            hooks.add(module.hook(change).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
                Object result = chain.proceed();
                ViewGroup container = (ViewGroup) chain.getThisObject();
                standaloneInstants.add(container);
                if (adjustsCard()) applyStandaloneInstant(container);
                return result;
            }));
    }

    private void applyStandaloneInstant(ViewGroup container) {
        if (!Boolean.TRUE.equals(Reflect.call(instantExpanded, container))) return;
        View body = (View) Reflect.call(instantView, container);
        if (body != null && body.getParent() == container) {
            adjustWidth(body, false);
            template(body);
        }
    }

    void installGap(HookEntry module, NativeCameraCard cards,
                    List<XposedInterface.HookHandle> hooks)
            throws ReflectiveOperationException {
        Class<?> list = cards.containerType;
        Class<?> decoration = list.getDeclaredMethod("getCardItemDecoration").getReturnType();
        Method offsets = Reflect.uniqueMethod(decoration, m -> m.getName().equals("getItemOffsets")
                && m.getReturnType() == void.class && m.getParameterCount() == 4
                && m.getParameterTypes()[0] == Rect.class && m.getParameterTypes()[1] == View.class
                && ViewGroup.class.isAssignableFrom(m.getParameterTypes()[2])
                && m.getParameterTypes()[2].isAssignableFrom(list));
        invalidateDecorations = Reflect.method(list, "invalidateItemDecorations", void.class);
        hooks.add(module.hook(offsets).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept(chain -> {
                    Object result = chain.proceed();
                    if (options.gap() != 0 && list.isInstance(chain.getArg(2))) {
                        View row = (View) chain.getArg(1);
                        if (cards.cardType.isInstance(row)) {
                            Rect rect = (Rect) chain.getArg(0);
                            rect.top = rowGap(rect.top, cards.itemPosition(chain.getArg(2), row), pixels(row, options.gap()));
                        }
                    }
                    return result;
                }));
    }

    static int rowGap(int stock, int position, int adjustment) {
        return position > 0 ? Math.max(0, stock + adjustment) : stock;
    }

    void afterListLayout(ViewGroup list) {
        adjustWidth(list, true);
        int gap = options.gap();
        Integer old = gap == 0 ? listGaps.remove(list) : listGaps.put(list, gap);
        if ((old == null ? 0 : old) != gap && invalidateDecorations != null)
            list.post(() -> Reflect.call(invalidateDecorations, list));
    }

    // The regular list updater always writes a fresh stock width. Instant views can retain
    // the same parameters across multiple callbacks, including an interrupted transition.
    void adjustWidth(View view, boolean fresh) {
        ViewGroup.LayoutParams lp = view.getLayoutParams();
        if (lp == null || lp.width <= 0 || options.side() == 0) return;
        Width old = widths.get(view);
        int stock = !fresh && old != null && old.params.get() == lp && old.applied == lp.width ? old.stock : lp.width;
        int available = view.getResources().getDisplayMetrics().widthPixels;
        WindowInsets insets = view.getRootWindowInsets();
        if (insets != null) {
            var safe = insets.getInsetsIgnoringVisibility(WindowInsets.Type.displayCutout() | WindowInsets.Type.systemBars());
            available -= 2 * Math.max(safe.left, safe.right);
        }
        int value = CardSpacing.width(stock, available, pixels(view, options.side()), pixels(view, 144));
        if (value != stock) {
            widths.put(view, new Width(new WeakReference<>(lp), stock, value));
            observe(view);
        } else widths.remove(view);
        if (lp.width != value) {
            lp.width = value;
            view.requestLayout();
        }
    }

    private static int pixels(View v, int dp) {
        return Math.round(dp * v.getResources().getDisplayMetrics().density);
    }

    void template(View body) {
        if (!hasPadding() || !(body instanceof ViewGroup group)) return;
        int x = pixels(body, options.horizontal()), y = pixels(body, options.vertical());
        View anchor = group.findViewWithTag("expandedView");
        if (anchor instanceof ViewGroup && anchor.getParent() instanceof FrameLayout) {
            inset(anchor, x, y, y);
            return;
        }
        TemplateIds names = ids.computeIfAbsent(body.getResources(), TemplateIds::new);
        // Media paints glass inside each pager page. Its measured content column is
        // handled by mediaMeasure, including pages inflated after the row was bound.
        if (find(group, names.album) != null) {
            // Also covers firmware where the media page has no named measurement override.
            View header = find(group, names.header);
            if (header != null && header.getParent() instanceof LinearLayout column
                    && column.getParent() instanceof FrameLayout mediaPage) mediaMeasure(mediaPage);
            return;
        }
        if (find(group, names.pager) != null) return;
        if (find(group, names.rows) instanceof LinearLayout rows) sectionRows(rows, x, y);
    }

    void sectionRows(LinearLayout rows, int x, int y) {
        int first = -1, last = -1;
        for (int i = 0; i < rows.getChildCount(); i++) {
            View row = rows.getChildAt(i);
            if (row.getVisibility() == View.VISIBLE && row instanceof ViewGroup group && group.getChildCount() > 0) {
                if (first < 0) first = i;
                last = i;
            }
        }
        for (int i = 0; i < rows.getChildCount(); i++) {
            View row = rows.getChildAt(i);
            if (row.getVisibility() == View.VISIBLE && row instanceof ViewGroup group && group.getChildCount() > 0)
                inset(row, x, i == first ? y : 0, i == last ? y : 0);
            else if (padding.containsKey(row)) release(row);
        }
    }

    void mediaMeasure(ViewGroup page) {
        TemplateIds names = ids.computeIfAbsent(page.getResources(), TemplateIds::new);
        View header = find(page, names.header);
        if (header == null || !(header.getParent() instanceof LinearLayout column) || column.getParent() != page) return;
        int x = pixels(page, options.horizontal()), y = pixels(page, options.vertical());
        // In this template the side gutters are margins inside the column. A bounded
        // expansion of the column consumes those gutters without resizing its glass sibling.
        int allowance = 0;
        View album = find(header, names.album), icon = find(header, names.icon);
        if (x < 0 && album != null && icon != null
                && album.getLayoutParams() instanceof ViewGroup.MarginLayoutParams left
                && icon.getLayoutParams() instanceof ViewGroup.MarginLayoutParams right)
            allowance = Math.max(0, Math.min(left.getMarginStart(), right.getMarginEnd()));
        inset(column, Math.max(-allowance, x), Math.max(0, y), Math.max(0, y), allowance);
        // The native vertical padding lives on the outer rows, not the column.
        if (y < 0) sectionRows(column, 0, y);
    }

    private static View find(View root, int id) { return id == 0 ? null : root.findViewById(id); }

    void inset(View v, int x, int top, int bottom) { inset(v, x, top, bottom, 0); }

    private void inset(View v, int x, int top, int bottom, int sideAllowance) {
        Padding old = padding.get(v);
        if (old == null && x == 0 && top == 0 && bottom == 0) return;
        boolean relative = v.isPaddingRelative();
        int start = relative ? v.getPaddingStart() : v.getPaddingLeft();
        int end = relative ? v.getPaddingEnd() : v.getPaddingRight();
        Rect current = new Rect(start, v.getPaddingTop(), end, v.getPaddingBottom());
        Rect stock = new Rect(current);
        if (old != null && old.relative == relative) {
            if (current.left == old.applied.left) stock.left = old.stock.left;
            if (current.top == old.applied.top) stock.top = old.stock.top;
            if (current.right == old.applied.right) stock.right = old.stock.right;
            if (current.bottom == old.applied.bottom) stock.bottom = old.stock.bottom;
        }
        Rect next = new Rect(Math.max(-sideAllowance, stock.left + x), Math.max(0, stock.top + top),
                Math.max(-sideAllowance, stock.right + x), Math.max(0, stock.bottom + bottom));
        if (next.equals(stock)) {
            padding.remove(v);
            if (!widths.containsKey(v) && observed.remove(v)) v.removeOnAttachStateChangeListener(this);
        }
        else if (old == null || !old.stock.equals(stock) || !old.applied.equals(next) || old.relative != relative) {
            padding.put(v, new Padding(stock, next, relative));
            observe(v);
        }
        if (!next.equals(current)) {
            if (relative) v.setPaddingRelative(next.left, next.top, next.right, next.bottom);
            else v.setPadding(next.left, next.top, next.right, next.bottom);
        }
    }

    private void observe(View v) {
        if (observed.add(v)) v.addOnAttachStateChangeListener(this);
    }

    private void release(View v) {
        if (padding.containsKey(v)) inset(v, 0, 0, 0);
        Width old = widths.remove(v);
        ViewGroup.LayoutParams lp = v.getLayoutParams();
        if (old != null && lp != null && old.params.get() == lp && lp.width == old.applied) {
            lp.width = old.stock;
            v.requestLayout();
        }
        if (observed.remove(v)) v.removeOnAttachStateChangeListener(this);
    }

    private void restoreViews() {
        for (View v : new ArrayList<>(padding.keySet())) if (v != null) release(v);
        for (View v : new ArrayList<>(widths.keySet())) if (v != null) release(v);
    }
    @Override public void onViewDetachedFromWindow(View v) { release(v); }
    @Override public void onViewAttachedToWindow(View v) { }
}
