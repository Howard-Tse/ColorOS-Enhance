package dev.lsposed.fluidcloud;

import android.content.res.Configuration;
import android.graphics.Rect;
import android.view.View;
import android.view.ViewGroup;
import java.lang.reflect.Method;
import java.util.WeakHashMap;

/** Move the native container frame so stock rendering, animation and gesture coordinates agree. */
final class NativeCapsulePosition {
    private final Class<?> rootType, containerType, capsuleType;
    final Method layout;
    final Method hidden;
    private final WeakHashMap<ViewGroup, Origin> origins = new WeakHashMap<>();
    private final Rect bounds = new Rect();
    Runnable overflowChanged = () -> { };
    private CapsulePosition portrait = CapsulePosition.OEM;
    private CapsulePosition landscape = CapsulePosition.OEM;

    private static final class Origin {
        int left, top, overflowY;
        final Rect capsuleBounds = new Rect();

        Origin(View view) {
            left = view.getLeft();
            top = view.getTop();
        }
    }

    NativeCapsulePosition(NativeCapsuleAppearance.Lookup lookup) throws ReflectiveOperationException {
        String prefix = NativeCapsuleAppearance.VIEWS;
        rootType = lookup.load(prefix + "CapsuleContainerRoot");
        containerType = lookup.load(prefix + "CapsuleContainer");
        capsuleType = lookup.load(prefix + "CapsuleView");
        layout = Reflect.method(containerType, "onLayout", void.class,
                boolean.class, int.class, int.class, int.class, int.class);
        if (layout.getDeclaringClass() != containerType) throw new NoSuchMethodException("Native capsule container layout");
        Method visibility;
        try {
            visibility = Reflect.method(capsuleType, "setHidden", void.class, boolean.class);
        } catch (NoSuchMethodException missing) {
            visibility = null;
        }
        hidden = visibility;
    }

    void afterLayout(ViewGroup container) {
        if (!rootType.isInstance(container.getParent())) return;
        // FrameLayout has just assigned the stock frame. Read it now, even if the
        // outer root has not moved and will not dispatch a layout callback.
        Origin origin = origins.computeIfAbsent(container, Origin::new);
        origin.left = container.getLeft();
        origin.top = container.getTop();
        if (hasCustomPosition()) apply(container, origin);
    }

    void afterVisibility(View capsule) {
        if (!hasCustomPosition() || !containerType.isInstance(capsule.getParent())) return;
        ViewGroup container = (ViewGroup) capsule.getParent();
        if (!rootType.isInstance(container.getParent())) return;
        apply(container, origins.computeIfAbsent(container, Origin::new));
    }

    void refresh(CapsulePosition nextPortrait, CapsulePosition nextLandscape) {
        if (portrait.equals(nextPortrait) && landscape.equals(nextLandscape)) return;
        portrait = nextPortrait;
        landscape = nextLandscape;
        for (var entry : origins.entrySet()) {
            apply(entry.getKey(), entry.getValue());
        }
    }

    private boolean hasCustomPosition() {
        return !portrait.equals(CapsulePosition.OEM) || !landscape.equals(CapsulePosition.OEM);
    }

    private void apply(ViewGroup container, Origin origin) {
        int x = origin.left;
        int y = origin.top;
        int overflowY = 0;
        if (container.getParent() instanceof View root && root.getWidth() > 0 && root.getHeight() > 0) {
            boolean isLandscape = root.getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE;
            CapsulePosition position = isLandscape ? landscape : portrait;
            if (!position.equals(CapsulePosition.OEM)) {
                // The container may fill its parent. Clamp using the visible pill and arrows,
                // and leave the camera-only mini state in the stock frame.
                bounds.setEmpty();
                boolean showCapsule = false;
                for (int i = 0; i < container.getChildCount(); i++) {
                    View child = container.getChildAt(i);
                    if (child.getVisibility() != View.VISIBLE || child.getWidth() == 0 || child.getHeight() == 0) continue;
                    showCapsule |= capsuleType.isInstance(child);
                    bounds.union(child.getLeft(), child.getTop(), child.getRight(), child.getBottom());
                }
                // An empty frame is also used during handoff to the card. Remember the
                // last pill geometry until it returns or the camera-only mini replaces it.
                if (showCapsule) origin.capsuleBounds.set(bounds);
                else if (!bounds.isEmpty()) origin.capsuleBounds.setEmpty();
                Rect capsuleBounds = origin.capsuleBounds;
                if (!capsuleBounds.isEmpty()) {
                    float density = root.getResources().getDisplayMetrics().density;
                    x += CapsulePosition.fit(Math.round(position.horizontalDp() * density),
                            x + capsuleBounds.left, x + capsuleBounds.right, root.getWidth());
                    int requestedY = Math.round(position.verticalDp() * density);
                    int nativeY = CapsulePosition.fit(requestedY,
                            y + capsuleBounds.top, y + capsuleBounds.bottom, root.getHeight());
                    y += nativeY;
                    if (!isLandscape) overflowY = Math.max(0, requestedY - nativeY);
                }
            }
        }
        container.offsetLeftAndRight(x - container.getLeft());
        container.offsetTopAndBottom(y - container.getTop());
        if (origin.overflowY != overflowY) {
            origin.overflowY = overflowY;
            overflowChanged.run();
        }
    }

    int overflowY(View root) {
        if (!rootType.isInstance(root) || !(root instanceof ViewGroup group)
                || root.getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE) return 0;
        for (int i = 0; i < group.getChildCount(); i++) {
            Origin origin = origins.get(group.getChildAt(i));
            if (origin != null) return origin.overflowY;
        }
        return 0;
    }

    void close() {
        refresh(CapsulePosition.OEM, CapsulePosition.OEM);
        origins.clear();
    }
}
