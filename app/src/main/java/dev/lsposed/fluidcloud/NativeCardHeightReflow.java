package dev.lsposed.fluidcloud;

import android.animation.ValueAnimator;
import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.TimeInterpolator;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import io.github.libxposed.api.XposedInterface;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.WeakHashMap;
import java.util.function.Predicate;
import java.util.function.ToIntFunction;

/** Finishes the native list offsets after CardView's height animator clears itself. */
final class NativeCardHeightReflow {
    private static final String STOCK_HEIGHT_ANIMATION = "cardViewLayoutAnimatorSupport";

    static void install(HookEntry module, NativeCameraCard cards, ArrayList<XposedInterface.HookHandle> hooks)
            throws ReflectiveOperationException {
        Class<?> row = cards.cardType;
        Class<?> list = cards.containerType;
        var animator = Reflect.uniqueField(row, field -> Reflect.instance(field) && field.getType() == ValueAnimator.class);
        Method attach = Reflect.method(list, "onAttachedToWindow", void.class);
        Method body = Reflect.method(row, "getContentView", View.class);
        Method actualHeight = Reflect.method(row, "getActualHeight", int.class);
        Method blocked = Reflect.method(row, "getBlockViewState", boolean.class);
        Class<?> itemAnimation = Reflect.type(row.getClassLoader(), "androidx.recyclerview.widget.RecyclerView$ItemAnimator");
        Method itemAnimator = Reflect.method(list, "getItemAnimator", itemAnimation);
        Method running = Reflect.method(itemAnimator.getReturnType(), "isRunning", boolean.class);
        var visibleHeights = new WeakHashMap<View, Integer>();
        Predicate<View> resizing = view -> row.isInstance(view)
                && (visibleHeights.containsKey(view) || Reflect.get(animator, view) != null);
        var offsets = new Offsets(row::isInstance, resizing, view -> {
            Integer height = visibleHeights.get(view);
            return height == null ? (int) Reflect.call(actualHeight, view) : height;
        }, view -> {
            View content = (View) Reflect.call(body, view);
            return content == null ? 0 : content.getBottom();
        });
        NativeResize motion;
        try { motion = new NativeResize(module, row, cards, animator, body, blocked, offsets, visibleHeights); }
        catch (ReflectiveOperationException | RuntimeException | LinkageError unavailable) {
            module.error("Native card height reflow size animation unavailable", unavailable);
            motion = null;
        }
        NativeResize resize = motion;
        var observed = new WeakHashMap<ViewGroup, ViewTreeObserver.OnPreDrawListener>();
        java.util.function.Consumer<ViewGroup> observe = container -> {
            if (observed.containsKey(container)) return;
            var reference = new WeakReference<>(container);
            ViewTreeObserver.OnPreDrawListener listener = () -> {
                ViewGroup current = reference.get();
                if (current != null) {
                    Object moves = Reflect.call(itemAnimator, current);
                    boolean moving = moves != null && Boolean.TRUE.equals(Reflect.call(running, moves));
                    if (resize != null) resize.observe(current, moving);
                    if (!moving) offsets.update(current);
                }
                return true;
            };
            observed.put(container, listener);
            container.getViewTreeObserver().addOnPreDrawListener(listener);
        };
        hooks.add(module.hook(attach).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
            Object result = chain.proceed(); // The native listener is registered ahead of ours.
            observe.accept((ViewGroup) chain.getThisObject());
            return result;
        }));
        hooks.add(module.hook(cards.cardLayout).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
            View card = (View) chain.getThisObject();
            if (!(card.getParent() instanceof ViewGroup parent) || !list.isInstance(parent) || !card.isShown()
                    || Boolean.TRUE.equals(Reflect.call(blocked, card))) return chain.proceed();
            observe.accept(parent);
            View content = (View) Reflect.call(body, card);
            if (content == null || content.getHeight() <= 0) return chain.proceed();
            int before = content.getHeight();
            // Leave existing template tags alone. Let untagged content opt into the
            // same OEM animation during a genuine resize, with its original easing.
            boolean optIn = content.getTag() == null && Math.abs(before - content.getMeasuredHeight()) >= 3;
            if (optIn) content.setTag(STOCK_HEIGHT_ANIMATION);
            try {
                Object result = chain.proceed();
                if (before != content.getHeight() && card.getParent() instanceof ViewGroup container)
                    offsets.requestLayout(container);
                return result;
            } finally {
                if (optIn && STOCK_HEIGHT_ANIMATION.equals(content.getTag())) content.setTag(null);
            }
        }));
        hooks.add(module.hook(cards.detach).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
            ViewGroup container = (ViewGroup) chain.getThisObject();
            ViewTreeObserver.OnPreDrawListener listener = observed.remove(container);
            if (listener != null) container.getViewTreeObserver().removeOnPreDrawListener(listener);
            if (resize != null) resize.detach(container);
            offsets.clear(container);
            return chain.proceed();
        }));
    }

    /**
     * The native template may relayout and cancel CardView's height animator as
     * it swaps content. Keep the height clock alive across that swap, using the
     * native frame drawer and interpolator for both material and list spacing.
     */
    static final class NativeResize {
        private record Size(WeakReference<View> body, int width, int height) { }
        private final HookEntry module;
        private final Class<?> row;
        private final NativeCameraCard cards;
        private final Field animator;
        private final Method body, blocked, draw, finish, blur;
        private final TimeInterpolator easing;
        private final Offsets offsets;
        private final WeakHashMap<View, Integer> visibleHeights;
        private final WeakHashMap<View, Size> sizes = new WeakHashMap<>();
        private final WeakHashMap<View, ValueAnimator> animations = new WeakHashMap<>();
        private boolean supported = true;
        private boolean reported;

        NativeResize(HookEntry module, Class<?> row, NativeCameraCard cards, Field animator, Method body, Method blocked,
                     Offsets offsets, WeakHashMap<View, Integer> visibleHeights)
                throws ReflectiveOperationException {
            this.module = module;
            this.row = row;
            this.cards = cards;
            this.animator = animator;
            this.body = body;
            this.blocked = blocked;
            this.offsets = offsets;
            this.visibleHeights = visibleHeights;
            draw = Reflect.uniqueMethod(row, method -> Modifier.isStatic(method.getModifiers())
                    && method.getReturnType() == void.class && method.getParameterCount() == 2
                    && method.getParameterTypes()[0] == row && method.getParameterTypes()[1] == ValueAnimator.class);
            easing = (TimeInterpolator) Reflect.get(Reflect.uniqueField(row, field ->
                    field.getDeclaringClass() == row && Modifier.isStatic(field.getModifiers())
                            && TimeInterpolator.class.isAssignableFrom(field.getType())), null);
            Method cleanup;
            try { cleanup = Reflect.method(row, cards.alternateLegacy ? "l" : "m", void.class); }
            catch (NoSuchMethodException unavailable) { cleanup = null; }
            finish = cleanup;
            blur = cards.blurBounds;
        }

        void observe(ViewGroup list, boolean moving) {
            if (!supported) return;
            for (int i = 0; i < list.getChildCount(); i++) {
                View card = list.getChildAt(i);
                if (!row.isInstance(card)) continue;
                View content = (View) Reflect.call(body, card);
                if (content == null || card.getHeight() <= 0) continue;
                Size previous = sizes.get(card);
                if (previous != null && previous.body().get() == content && previous.width() == card.getWidth()
                        && previous.height() == card.getHeight()) continue;
                sizes.put(card, new Size(new WeakReference<>(content), card.getWidth(), card.getHeight()));
                if (previous == null || previous.body().get() != content || previous.width() != card.getWidth()
                        || moving || Math.abs(previous.height() - card.getHeight()) < 3
                        || card.getScaleX() != 1f || card.getScaleY() != 1f || !card.isShown()
                        || Boolean.TRUE.equals(Reflect.call(blocked, card)) || !ValueAnimator.areAnimatorsEnabled()) continue;
                try { animate(card, list, visibleHeights.getOrDefault(card, previous.height())); }
                catch (RuntimeException | LinkageError failure) {
                    disable(failure);
                    detach(list);
                    return;
                }
            }
        }

        private void applyFrame(View card, ViewGroup list) {
            offsets.update(list);
            // Updating a blur rectangle only invalidates the background. Re-record
            // the card too so the new material and the translated rows commit together.
            card.invalidate();
            list.invalidate();
        }

        private void animate(View card, ViewGroup list, int start) {
            ValueAnimator previous = animations.remove(card);
            if (previous != null) previous.cancel();
            if (Reflect.get(animator, card) instanceof ValueAnimator nativeAnimation) nativeAnimation.cancel();
            visibleHeights.put(card, start);
            ValueAnimator next = ValueAnimator.ofInt(start, card.getHeight());
            // A shared-template page swap has a 300 ms native content crossfade.
            // Resize those two material layers over the same interval.
            boolean shared = cards.sharedTemplateTransition(card);
            next.setDuration(shared ? 300 : 200);
            next.setInterpolator(easing);
            next.addUpdateListener(frame -> {
                if (card.getParent() != list || !list.isAttachedToWindow()
                        || card.getScaleX() != 1f || card.getScaleY() != 1f || card.getAlpha() < 1f) {
                    frame.cancel();
                    return;
                }
                try {
                    int height = (int) frame.getAnimatedValue();
                    visibleHeights.put(card, height);
                    Reflect.call(draw, null, card, frame);
                    if (shared) cards.resizeSharedMaterials((ViewGroup) card, height);
                    applyFrame(card, list);
                    if (!reported && height != start && height != card.getHeight()) {
                        reported = true;
                        module.info("Native card height reflow rendered an intermediate height");
                    }
                }
                catch (RuntimeException | LinkageError failure) {
                    disable(failure);
                    frame.cancel();
                }
            });
            next.addListener(new AnimatorListenerAdapter() {
                @Override public void onAnimationEnd(Animator animation) {
                    if (animations.remove(card, next)) {
                        visibleHeights.remove(card);
                        try {
                            if (shared) cards.finishSharedMaterialResize(card);
                            if (Reflect.get(animator, card) == null && card.getParent() == list
                                    && !Boolean.TRUE.equals(Reflect.call(blocked, card))) finishFrame(card);
                        } catch (RuntimeException | LinkageError failure) {
                            disable(failure);
                        }
                        applyFrame(card, list);
                    }
                }
            });
            animations.put(card, next);
            next.start(); // ValueAnimator outputs the start value in this frame.
        }

        private void disable(Throwable error) {
            if (supported) module.error("Native card height reflow size animation failed", error);
            supported = false;
        }

        private void finishFrame(View card) {
            if (finish != null) Reflect.call(finish, card);
            else Reflect.call(blur, card, new android.graphics.Rect(0, 0, card.getWidth(), card.getHeight()));
            card.invalidateOutline();
        }

        void detach(ViewGroup list) {
            for (int i = 0; i < list.getChildCount(); i++) {
                View card = list.getChildAt(i);
                sizes.remove(card);
                ValueAnimator running = animations.get(card);
                if (running != null) running.cancel();
            }
        }
    }

    static final class Offsets {
        private final Predicate<View> row;
        private final Predicate<View> resizing;
        private final ToIntFunction<View> actualHeight;
        private final ToIntFunction<View> contentBottom;
        private final WeakHashMap<ViewGroup, WeakHashMap<View, Integer>> owned = new WeakHashMap<>();
        private final WeakHashMap<ViewGroup, Boolean> pendingLayout = new WeakHashMap<>();

        Offsets(Predicate<View> row, Predicate<View> resizing, ToIntFunction<View> actualHeight,
                ToIntFunction<View> contentBottom) {
            this.row = row;
            this.resizing = resizing;
            this.actualHeight = actualHeight;
            this.contentBottom = contentBottom;
        }

        void update(ViewGroup list) {
            var shifts = owned.get(list);
            int offset = 0;
            for (int i = 0; i < list.getChildCount(); i++) {
                View view = list.getChildAt(i);
                Integer previous = shifts == null ? null : shifts.get(view);
                float current = view.getTranslationY();
                boolean ours = previous != null && previous == current;
                if (offset != 0 && row.test(view) && (current == offset || current == 0 || ours)) {
                    // The ordinary path is already updated by ColorOS. Fill in a
                    // missed frame using its animated height, without a second animator.
                    if (current != offset) view.setTranslationY(offset);
                    if (shifts == null) {
                        shifts = new WeakHashMap<>();
                        owned.put(list, shifts);
                    }
                    if (previous == null || previous != offset) shifts.put(view, offset);
                } else if (previous != null) {
                    if (offset == 0 && ours) view.setTranslationY(0);
                    shifts.remove(view);
                }
                if (resizing.test(view)) offset += actualHeight.applyAsInt(view) - view.getHeight();
            }
        }

        void requestLayout(ViewGroup container) {
            if (pendingLayout.put(container, Boolean.TRUE) != null) return;
            container.postOnAnimation(() -> {
                if (pendingLayout.remove(container) != null && container.isAttachedToWindow()
                        && staleLayout(container)) container.requestLayout();
            });
        }

        boolean staleLayout(ViewGroup list) {
            int bottom = Integer.MIN_VALUE;
            for (int i = 0; i < list.getChildCount(); i++) {
                View child = list.getChildAt(i);
                if (!row.test(child) || child.getVisibility() != View.VISIBLE || child.getHeight() == 0) continue;
                if (contentBottom.applyAsInt(child) > child.getHeight()) {
                    child.forceLayout();
                    return true;
                }
                if (child.getMeasuredHeight() != child.getHeight() || child.getTop() < bottom) return true;
                bottom = child.getBottom();
            }
            return false;
        }

        void clear(ViewGroup list) {
            pendingLayout.remove(list);
            var shifts = owned.remove(list);
            if (shifts == null) return;
            shifts.forEach((view, offset) -> {
                if (view != null && view.getParent() == list && view.getTranslationY() == offset)
                    view.setTranslationY(0);
            });
        }
    }
}
