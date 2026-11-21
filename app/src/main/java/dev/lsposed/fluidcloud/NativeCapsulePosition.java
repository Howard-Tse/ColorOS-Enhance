package dev.lsposed.fluidcloud;

import android.graphics.Rect;
import android.view.View;
import android.view.ViewGroup;
import java.util.ArrayList;
import java.util.WeakHashMap;

/** Move the native container frame so stock rendering, animation and gesture coordinates agree. */
final class NativeCapsulePosition {
    private final Class<?> rootType, containerType, capsuleType;
    private final WeakHashMap<View, CapsulePosition> positions = new WeakHashMap<>();
    private final View.OnLayoutChangeListener layout =
            (root, l, t, r, b, oldL, oldT, oldR, oldB) -> apply((ViewGroup) root, positions.get(root));

    NativeCapsulePosition(NativeCapsuleAppearance.Lookup lookup) throws ClassNotFoundException {
        String prefix = NativeCapsuleAppearance.VIEWS;
        rootType = lookup.load(prefix + "CapsuleContainerRoot");
        containerType = lookup.load(prefix + "CapsuleContainer");
        capsuleType = lookup.load(prefix + "CapsuleView");
    }

    void refresh(View root, CapsulePosition next) {
        if (!rootType.isInstance(root)) return;
        CapsulePosition current = positions.get(root);
        if (next.equals(CapsulePosition.OEM)) {
            if (current == null) return;
            positions.remove(root);
            root.removeOnLayoutChangeListener(layout);
        } else {
            if (next.equals(current)) return;
            positions.put(root, next);
            if (current == null) root.addOnLayoutChangeListener(layout);
        }
        // FrameLayout computes the original center on each layout, including when we disable the feature.
        root.requestLayout();
    }

    private void apply(ViewGroup root, CapsulePosition position) {
        if (position == null || root.getWidth() == 0 || root.getHeight() == 0) return;
        for (int i = 0; i < root.getChildCount(); i++) {
            if (!(root.getChildAt(i) instanceof ViewGroup container) || !containerType.isInstance(container)) continue;
            // The container itself can be MATCH_PARENT in height. Use its drawn capsule and arrows
            // to limit movement. Leave the camera-only mini state at its native position.
            Rect bounds = new Rect();
            boolean hasCapsule = false;
            for (int index = 0; index < container.getChildCount(); index++) {
                View child = container.getChildAt(index);
                if (child.getVisibility() != View.VISIBLE || child.getWidth() == 0 || child.getHeight() == 0) continue;
                hasCapsule |= capsuleType.isInstance(child);
                bounds.union(child.getLeft(), child.getTop(), child.getRight(), child.getBottom());
            }
            if (!hasCapsule) return;
            bounds.offset(container.getLeft(), container.getTop());
            float density = root.getResources().getDisplayMetrics().density;
            container.offsetLeftAndRight(CapsulePosition.fit(Math.round(position.horizontalDp() * density),
                    bounds.left, bounds.right, root.getWidth()));
            container.offsetTopAndBottom(CapsulePosition.fit(Math.round(position.verticalDp() * density),
                    bounds.top, bounds.bottom, root.getHeight()));
            return;
        }
    }

    void close() {
        for (View root : new ArrayList<>(positions.keySet())) refresh(root, CapsulePosition.OEM);
    }
}
