package dev.howard.aodenhance;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewTreeObserver;
import android.graphics.Rect;
import java.util.Objects;
import java.util.WeakHashMap;
import java.util.ArrayList;
import java.lang.ref.WeakReference;

final class ViewOverrides {
    private final AodModule module;
    private final AlphaLedger<View> ledger = new AlphaLedger<>();
    private boolean wallpaperBackgroundDim;
    private final WeakHashMap<View, Boolean> roots = new WeakHashMap<>();
    private final WeakHashMap<View, Boolean> clockRoots = new WeakHashMap<>();
    private final WeakHashMap<View, ValueAnimator> animations = new WeakHashMap<>();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ThreadLocal<Boolean> writing = ThreadLocal.withInitial(() -> false);
    private volatile boolean panoramic;
    private final AodUiState uiState = new AodUiState();
    private boolean transitionsReady;
    private final Runnable refresh = this::refreshNow;

    ViewOverrides(AodModule module) { this.module = module; }
    void useWallpaperBackgroundDim() { wallpaperBackgroundDim = true; }
    void enableTransitions() { transitionsReady = true; }
    void setUiState(String state, boolean nativeAnimation) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post(() -> setUiState(state, nativeAnimation));
            return;
        }
        String previous = uiState.name();
        if (!uiState.update(state)) return;
        boolean animate = module.config.animate && ElementFade.shouldAnimate(previous, state, nativeAnimation);
        module.trace("元素过渡", previous + " -> " + state + " nativeAnim=" + nativeAnimation + " fade=" + animate
                + " hideElements=" + uiState.hideElements());
        panoramic = uiState.isPanoramic();
        refreshNow(animate);
    }
    boolean isPanoramic() { return panoramic; }
    void refresh() {
        main.removeCallbacks(refresh);
        if (Looper.myLooper() == Looper.getMainLooper()) refreshNow();
        else main.post(refresh);
    }
    float interceptAlpha(View view, float requested) {
        if (writing.get()) return requested;
        AlphaLedger.Entry entry = ledger.get(view);
        if (entry == null) return requested;
        ledger.request(view, requested);
        return AodPolicy.alpha(entry.element, requested, module.config, panoramic);
    }
    Rect interceptClip(View view, Rect requested) {
        AlphaLedger.Entry entry = ledger.get(view);
        if (writing.get() || entry == null) return requested;
        ledger.requestClip(view, fromRect(requested));
        return toRect(entry.fade.clip(entry.nativeClip));
    }
    float interceptTransitionAlpha(View view, float requested) {
        if (!transitionsReady) return requested;
        AlphaLedger.Entry entry = ledger.get(view);
        if (writing.get() || entry == null || "background".equals(entry.element)) return requested;
        entry.nativeTransitionAlpha = requested;
        return entry.fade.compose(requested);
    }
    Float nativeTransitionAlpha(View view) {
        if (!transitionsReady || writing.get()) return null;
        AlphaLedger.Entry entry = ledger.get(view);
        return entry == null || "background".equals(entry.element) ? null : entry.nativeTransitionAlpha;
    }
    private static AlphaLedger.Clip fromRect(Rect rect) {
        return rect == null ? null : new AlphaLedger.Clip(rect.left, rect.top, rect.right, rect.bottom);
    }
    private static Rect toRect(AlphaLedger.Clip clip) {
        return clip == null ? null : new Rect(clip.left(), clip.top(), clip.right(), clip.bottom());
    }
    void shadeAttached(View root) {
        if (!roots.containsKey(root)) {
            roots.put(root, true);
            // Event-driven discovery also handles theme reinflation; no polling or wake locks.
            ViewTreeObserver.OnGlobalLayoutListener listener = () -> {
                try { discover(root); } catch (RuntimeException e) { module.warnOnce("layout", e); }
            };
            root.getViewTreeObserver().addOnGlobalLayoutListener(listener);
            root.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
                @Override public void onViewAttachedToWindow(View view) { discover(view); }
                @Override public void onViewDetachedFromWindow(View view) {
                    roots.remove(view);
                    view.getViewTreeObserver().removeOnGlobalLayoutListener(listener);
                    view.removeOnAttachStateChangeListener(this);
                }
            });
        }
        discover(root);
    }
    void statusInflated(View status) {
        register(find(status, "statusIcons"), "status");
        register(find(status, "battery"), "battery");
        register(find(status, "keyguard_carrier_text"), "carrier");
    }
    void widgetView(View view) {
        if (Looper.myLooper() != Looper.getMainLooper()) { main.post(() -> widgetView(view)); return; }
        register(view, "widgets");
    }
    void clockRoot(View root) {
        if (Looper.myLooper() != Looper.getMainLooper()) { main.post(() -> clockRoot(root)); return; }
        if (!clockRoots.containsKey(root)) {
            clockRoots.put(root, true);
            ViewTreeObserver.OnGlobalLayoutListener listener = () -> discoverClockSafely(root);
            root.getViewTreeObserver().addOnGlobalLayoutListener(listener);
            root.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
                @Override public void onViewAttachedToWindow(View view) {
                    // A detached plugin may be reused after a theme/layout transition.
                    view.getViewTreeObserver().removeOnGlobalLayoutListener(listener);
                    view.getViewTreeObserver().addOnGlobalLayoutListener(listener);
                    discoverClockSafely(view);
                }
                @Override public void onViewDetachedFromWindow(View view) {
                    view.getViewTreeObserver().removeOnGlobalLayoutListener(listener);
                }
            });
        }
        discoverClockSafely(root);
    }
    private void discoverClockSafely(View root) {
        try { discoverClock(root); }
        catch (RuntimeException error) { module.warnOnce("日期元素发现失败", error); }
    }
    private void discoverClock(View view) {
        ClockElements.discover(view, this::register);
    }
    private void discover(View root) {
        // Modern clocks can be composed into the wallpaper surface below this entire window.
        // Never cover that surface with a SystemUI mask; dim its background render pass instead.
        if (!wallpaperBackgroundDim) register(find(root, "oplus_background_mask"), "background");
        View status = find(root, "keyguard_status_bar");
        if (status != null) statusInflated(status);
    }
    private View find(View root, String name) {
        int id = root.getResources().getIdentifier(name, "id", "com.android.systemui");
        return id == 0 ? null : root.findViewById(id);
    }
    private void register(View view, String element) {
        if (view == null || ledger.get(view) != null) return;
        float transitionAlpha = view.getTransitionAlpha();
        ledger.register(view, element, view.getAlpha());
        ledger.requestClip(view, fromRect(view.getClipBounds()));
        ledger.get(view).nativeTransitionAlpha = transitionAlpha;
        if (!"background".equals(element)) view.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override public void onViewAttachedToWindow(View attached) { apply(attached, false); }
            @Override public void onViewDetachedFromWindow(View detached) {
                cancelAnimation(detached);
                apply(detached, false);
            }
        });
        module.info("发现元素 " + element);
        apply(view, false);
    }
    private void refreshNow() { refreshNow(false); }
    private void refreshNow(boolean animate) {
        try {
            for (View root : new ArrayList<>(roots.keySet())) if (root != null) discover(root);
            for (View root : new ArrayList<>(clockRoots.keySet())) if (root != null) discoverClock(root);
            for (View view : ledger.keys()) if (view != null) apply(view, animate);
            if (panoramic) for (View view : ledger.keys()) {
                if (view == null) continue;
                AlphaLedger.Entry entry = ledger.get(view);
                module.trace("视图-" + entry.element, "shown=" + view.isShown() + " alpha=" + view.getAlpha()
                        + " clip=" + view.getClipBounds());
            }
        } catch (RuntimeException e) { module.warnOnce("refresh", e); }
    }
    private void apply(View view, boolean animate) {
        AlphaLedger.Entry entry = ledger.get(view);
        if (entry == null) return;
        if (!"background".equals(entry.element)) {
            boolean hidden = AodPolicy.hide(entry.element, module.config, uiState.hideElements());
            ElementFade.Change change = entry.fade.retarget(hidden, animate && transitionsReady
                    && view.isAttachedToWindow() && ValueAnimator.areAnimatorsEnabled());
            if (change != ElementFade.Change.KEEP) {
                cancelAnimation(view);
                if (change == ElementFade.Change.ANIMATE) startAnimation(view, entry);
            }
        }
        render(view, entry);
    }
    private void cancelAnimation(View view) {
        ValueAnimator animator = animations.remove(view);
        if (animator == null) return;
        // Remove callbacks first: cancel() also emits onAnimationEnd, which must not settle a reversal.
        animator.removeAllUpdateListeners();
        animator.removeAllListeners();
        animator.cancel();
    }
    private void startAnimation(View view, AlphaLedger.Entry entry) {
        WeakReference<View> weakView = new WeakReference<>(view);
        ValueAnimator animator = ValueAnimator.ofFloat(0, 1);
        animator.setDuration(entry.fade.durationMillis());
        animator.addUpdateListener(frame -> {
            View target = weakView.get();
            if (target == null) { frame.cancel(); return; }
            if (animations.get(target) != frame) return;
            entry.fade.advance((Float) frame.getAnimatedValue());
            render(target, entry);
        });
        animator.addListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(Animator animation) {
                View target = weakView.get();
                if (target == null || animations.get(target) != animation) return;
                animations.remove(target);
                entry.fade.advance(1);
                render(target, entry);
                module.trace("淡变-" + entry.element, "finished opacity=" + entry.fade.opacity());
            }
        });
        animations.put(view, animator);
        animator.start();
    }
    private void render(View view, AlphaLedger.Entry entry) {
        float target = AodPolicy.alpha(entry.element, entry.nativeAlpha, module.config, panoramic);
        Rect clip = toRect(entry.fade.clip(entry.nativeClip));
        writing.set(true);
        try {
            if ("background".equals(entry.element) && Float.compare(view.getAlpha(), target) != 0) view.setAlpha(target);
            if (!"background".equals(entry.element)) {
                float drawingAlpha = entry.fade.compose(entry.nativeTransitionAlpha);
                // Set the fade multiplier before removing the empty clip to prevent a bright first frame.
                if (transitionsReady && Float.compare(view.getTransitionAlpha(), drawingAlpha) != 0)
                    view.setTransitionAlpha(drawingAlpha);
                if (!Objects.equals(view.getClipBounds(), clip)) view.setClipBounds(clip);
            }
        } finally { writing.set(false); }
    }
}
