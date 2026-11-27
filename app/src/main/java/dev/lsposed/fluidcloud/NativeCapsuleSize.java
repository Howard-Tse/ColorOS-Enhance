package dev.lsposed.fluidcloud;

import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.WeakHashMap;

/** Changes native measured bounds, so native hit regions and morph anchors use the same size. */
final class NativeCapsuleSize {
    private static final String CONSTRAINT_LAYOUT = "androidx.constraintlayout.widget.ConstraintLayout";
    final Method measure;
    final Method templateMeasure;
    private final Method measured = Reflect.method(View.class, "setMeasuredDimension", void.class, int.class, int.class);
    private final Class<?> capsule;
    private final WeakHashMap<View, Size> views = new WeakHashMap<>();
    private final WeakHashMap<View, WeakReference<View>> probed = new WeakHashMap<>();
    private final ThreadLocal<View> measuringCapsule = new ThreadLocal<>();
    private final View.OnLayoutChangeListener layout = this::layout;
    private record Size(CapsuleSize options, int widthLimit, int height) { }

    NativeCapsuleSize(NativeCapsuleAppearance.Lookup lookup) throws ReflectiveOperationException {
        capsule = lookup.load(NativeCapsuleAppearance.VIEWS + "CapsuleView");
        measure = Reflect.method(capsule, "onMeasure", void.class, int.class, int.class);
        if (measure.getDeclaringClass() != capsule) throw new NoSuchMethodException("Native capsule measurement");
        templateMeasure = findTemplateMeasure(lookup);
    }

    private static Method findTemplateMeasure(NativeCapsuleAppearance.Lookup lookup) {
        try {
            Class<?> template = lookup.load(CONSTRAINT_LAYOUT);
            if (!ViewGroup.class.isAssignableFrom(template)) return null;
            Method method = Reflect.method(template, "onMeasure", void.class, int.class, int.class);
            return method.getDeclaringClass() == template ? method : null;
        } catch (ReflectiveOperationException | LinkageError missing) { return null; }
    }

    boolean tracks(View view) { return views.containsKey(view); }

    View beginNativeMeasure(View view) {
        var probe = probed.remove(view);
        View target = probe == null ? null : probe.get();
        if (target != null && probeOwner(target) == view) {
            // The width probe cached custom bounds under the native measurement
            // specs. Invalidate them through the wrappers before the next baseline.
            for (View child = target; child != view; child = (View) child.getParent()) child.forceLayout();
        }
        View previous = measuringCapsule.get();
        measuringCapsule.set(view);
        return previous;
    }

    void endNativeMeasure(View previous) {
        if (previous == null) measuringCapsule.remove();
        else measuringCapsule.set(previous);
    }

    @android.annotation.SuppressLint("DiscouragedApi")
    void afterMeasure(View view, int widthSpec, int heightSpec, CapsuleSize size, int externalLimit) {
        if (size.original()) {
            if (views.remove(view) != null) view.removeOnLayoutChangeListener(layout);
            return;
        }
        int w = view.getMeasuredWidth(), h = view.getMeasuredHeight();
        int widthLimit = Math.min(externalLimit, limit(widthSpec, view.getResources().getDisplayMetrics().widthPixels));
        int heightLimit = h;
        int id = view.getResources().getIdentifier("status_bar_height", "dimen", "android");
        if (id != 0) heightLimit = Math.max(h, view.getResources().getDimensionPixelSize(id));
        heightLimit = Math.min(heightLimit, limit(heightSpec, heightLimit));
        int width = resized(w, size.widthPercent(), widthLimit);
        int height = resized(h, size.heightPercent(), heightLimit);
        if (views.put(view, new Size(size, widthLimit, height)) == null) view.addOnLayoutChangeListener(layout);
        Reflect.call(measured, view, width | (view.getMeasuredWidthAndState() & View.MEASURED_STATE_MASK),
                height | (view.getMeasuredHeightAndState() & View.MEASURED_STATE_MASK));
        if (width > 0 && height > 0) {
            View template = template(view);
            if (template != null) measureTemplate(view, template, width, height);
            else {
                List<View> path = wrappedTemplate(view);
                if (!path.isEmpty()) measureWrappedTemplate(view, path, width, height, false);
            }
        }
    }

    private static int limit(int spec, int fallback) {
        return View.MeasureSpec.getMode(spec) == View.MeasureSpec.UNSPECIFIED ? fallback : View.MeasureSpec.getSize(spec);
    }

    private static int resized(int nativeSize, int percent, int limit) {
        return Math.min(limit, CapsuleSize.dimension(nativeSize, percent, limit));
    }

    private boolean canReflow(View view) {
        return templateMeasure != null && templateMeasure.getDeclaringClass().isInstance(view)
                && view.getLayoutParams() instanceof FrameLayout.LayoutParams params && params.width < 0;
    }

    private View template(View view) {
        // Both native templates are attached as the single CapsuleView child. Their
        // constraints anchor the sides to the outer padding and a fixed camera spacer.
        if (!(view instanceof ViewGroup group) || group.getChildCount() != 1) return null;
        View template = group.getChildAt(0);
        return template.getVisibility() != View.GONE && canReflow(template) ? template : null;
    }

    private record Insets(int left, int top, int right, int bottom) {
        int horizontal() { return left + right; }
        int vertical() { return top + bottom; }
    }

    private static Insets insets(View capsule, View template) {
        var margin = (FrameLayout.LayoutParams) template.getLayoutParams();
        return new Insets(capsule.getPaddingLeft() + margin.leftMargin, capsule.getPaddingTop() + margin.topMargin,
                capsule.getPaddingRight() + margin.rightMargin, capsule.getPaddingBottom() + margin.bottomMargin);
    }

    private static int exact(int size) {
        return View.MeasureSpec.makeMeasureSpec(Math.max(0, size), View.MeasureSpec.EXACTLY);
    }

    private static void measureTemplate(View capsule, View template, int width, int height) {
        var inset = insets(capsule, template);
        template.measure(exact(width - inset.horizontal()), exact(height - inset.vertical()));
    }

    private List<View> wrappedTemplate(View capsule) {
        View root = onlyChild(capsule);
        if (!(root instanceof FrameLayout)) return List.of();
        List<View> path = new ArrayList<>();
        for (View cursor = root; cursor != null && path.size() < 6; cursor = onlyChild(cursor)) {
            path.add(cursor);
            if (cursor != root && cursor instanceof ViewGroup content && isSplitTemplate(content)) return path;
        }
        return List.of();
    }

    private static View onlyChild(View view) {
        if (!(view instanceof FrameLayout parent)) return null;
        View child = null;
        for (int index = 0; index < parent.getChildCount(); index++) {
            View candidate = parent.getChildAt(index);
            if (candidate.getVisibility() == View.GONE) continue;
            if (child != null || !(candidate.getLayoutParams() instanceof FrameLayout.LayoutParams)) return null;
            child = candidate;
        }
        return child;
    }

    private boolean isSplitTemplate(ViewGroup content) {
        if (!isConstraintLayout(content) || content.getChildCount() < 3
                || content.getChildAt(1).getClass() != View.class) return false;
        View first = content.getChildAt(0), last = content.getChildAt(2);
        // Both plugins and the SDK use a centered spacer. ColorOS 16 uses
        // another ConstraintLayout as the leading section of its plugin templates.
        return first instanceof LinearLayout && last instanceof LinearLayout
                || last instanceof FrameLayout && (first instanceof FrameLayout || isConstraintLayout(first));
    }

    private boolean isConstraintLayout(View view) {
        // The dynamically loaded SDK can define its own ConstraintLayout class.
        return view.getClass().getName().equals(CONSTRAINT_LAYOUT)
                || templateMeasure != null && templateMeasure.getDeclaringClass().isInstance(view);
    }

    private record OriginalSize(ViewGroup.LayoutParams params, int width, int height) {
        OriginalSize(ViewGroup.LayoutParams params) { this(params, params.width, params.height); }
        void restore() { params.width = width; params.height = height; }
    }

    private static boolean viewportMatches(View parent, List<View> path, int width, int height, boolean laidOut) {
        for (View child : path) {
            var inset = insets(parent, child);
            width = Math.max(0, width - inset.horizontal());
            height = Math.max(0, height - inset.vertical());
            if (child.getMeasuredWidth() != width || child.getMeasuredHeight() != height
                    || laidOut && (child.getWidth() != width || child.getHeight() != height)) return false;
            parent = child;
        }
        return true;
    }

    private static void measureWrappedTemplate(View capsule, List<View> path, int width, int height, boolean laidOut) {
        // The native template can restore its intrinsic height after an update
        // while the wrapper still measures at the correct outer dimensions.
        if (viewportMatches(capsule, path, width, height, laidOut)) return;
        for (View child : path) child.forceLayout();
        View root = path.get(0);
        var originals = new ArrayList<OriginalSize>(path.size() - 1);
        try {
            // The SDK writes intrinsic pixel sizes to its leaf and wrapper layout
            // params. Pass the allocated viewport through FrameLayout for this pass
            // and leave those intrinsic sizes available for the next native measure.
            for (int index = 1; index < path.size(); index++) {
                var params = path.get(index).getLayoutParams();
                originals.add(new OriginalSize(params));
                params.width = ViewGroup.LayoutParams.MATCH_PARENT;
                params.height = ViewGroup.LayoutParams.MATCH_PARENT;
            }
            measureTemplate(capsule, root, width, height);
        } finally {
            for (OriginalSize original : originals) original.restore();
        }
    }

    boolean isTemplateWidthProbe(View template, int widthSpec, int heightSpec) {
        // The native update callback measures the template on its own to obtain
        // the destination of the width animation. Keep normal parent measurement intrinsic.
        if (views.isEmpty() || View.MeasureSpec.getMode(heightSpec) != View.MeasureSpec.UNSPECIFIED
                || View.MeasureSpec.getMode(widthSpec) != View.MeasureSpec.AT_MOST) return false;
        View owner = probeOwner(template);
        if (owner == null || owner == measuringCapsule.get() || !tracks(owner)) return false;
        if (template(owner) == template) return true;
        List<View> path = wrappedTemplate(owner);
        return !path.isEmpty() && path.get(path.size() - 1) == template;
    }

    int[] templateProbeSpecs(View template) {
        View owner = probeOwner(template);
        if (owner == null) return null;
        Size size = views.get(owner);
        if (size == null || size.height() <= 0 || template.getMeasuredWidth() <= 0) return null;
        int horizontal = 0, vertical = 0;
        for (View child = template; child != owner; ) {
            View parent = (View) child.getParent();
            var inset = insets(parent, child);
            horizontal += inset.horizontal();
            vertical += inset.vertical();
            child = parent;
        }
        int width = resized(template.getMeasuredWidth() + horizontal, size.options().widthPercent(), size.widthLimit());
        probed.put(owner, new WeakReference<>(template));
        return new int[]{exact(width - horizontal), exact(size.height() - vertical)};
    }

    private View probeOwner(View template) {
        View child = template;
        for (int depth = 0; depth < 7; depth++) {
            if (!(child.getParent() instanceof FrameLayout parent)
                    || !(child.getLayoutParams() instanceof FrameLayout.LayoutParams)) return null;
            if (capsule.isInstance(parent)) return parent;
            child = parent;
        }
        return null;
    }

    private void layout(View view, int l, int t, int r, int b, int ol, int ot, int or, int ob) {
        if (!views.containsKey(view) || !(view instanceof ViewGroup group)) return;
        View template = template(view);
        List<View> path = template == null ? wrappedTemplate(view) : List.of();
        if (!path.isEmpty()) template = path.get(0);
        if (template != null) {
            // During a native transition the host can temporarily lay out the
            // capsule at its previous size. Respect that frame as well as the final one.
            var inset = insets(view, template);
            int width = Math.max(0, view.getWidth() - inset.horizontal());
            int height = Math.max(0, view.getHeight() - inset.vertical());
            if (!path.isEmpty()) measureWrappedTemplate(view, path, view.getWidth(), view.getHeight(), true);
            else if (template.getMeasuredWidth() != width || template.getMeasuredHeight() != height)
                template.measure(exact(width), exact(height));
            template.layout(inset.left(), inset.top(), inset.left() + width, inset.top() + height);
            return;
        }
        // Keep arbitrary provider views at their original dimensions.
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            if (child.getVisibility() == View.GONE) continue;
            child.offsetLeftAndRight((view.getWidth() - child.getWidth()) / 2 - child.getLeft());
            child.offsetTopAndBottom((view.getHeight() - child.getHeight()) / 2 - child.getTop());
        }
    }
    void refresh(View root, CapsuleSize size) {
        if (capsule.isInstance(root)) {
            Size previous = views.get(root);
            if (previous == null ? !size.original() : !size.equals(previous.options())) root.requestLayout();
        }
        if (root instanceof ViewGroup group) for (int i = 0; i < group.getChildCount(); i++) refresh(group.getChildAt(i), size);
    }
    void close() {
        for (View view : new ArrayList<>(views.keySet())) { view.removeOnLayoutChangeListener(layout); view.requestLayout(); }
        views.clear();
        probed.clear();
    }
}
