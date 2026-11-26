package dev.lsposed.fluidcloud;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.animation.PathInterpolator;
import io.github.libxposed.api.XposedInterface;
import java.lang.reflect.Field;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** Keeps the OEM tap and vertical gesture dispatch. Owns only an outward horizontal notification swipe. */
final class NativeNotificationCardTouch {
    private static final long AFTER_TOUCH_MS = 160;
    private static final PathInterpolator SETTLE = new PathInterpolator(.2f, .7f, .25f, 1f);
    private enum Route { UNDECIDED, NATIVE, PAGE, DISMISS }

    private final NotificationCloudFeature feature;
    private final Field cardItem, cardKey;
    private final XposedInterface.HookHandle dispatchHook;
    private final Map<View, Slide> slides = new IdentityHashMap<>();
    private final Runnable release;
    private Press press;
    private String releasedKey;

    NativeNotificationCardTouch(NotificationCloudFeature feature, ClassLoader loader) throws ReflectiveOperationException {
        this(feature, Reflect.type(loader, "com.oplus.systemui.plugins.seedling.card.ui.view.CardContainer"),
                Reflect.type(loader, "com.oplus.systemui.plugins.seedling.card.ui.view.CardView"),
                Reflect.type(loader, "com.oplus.systemui.plugins.seedling.card.ui.model.o"), "h");
    }

    NativeNotificationCardTouch(NotificationCloudFeature feature, Class<?> container, Class<?> card, Class<?> item,
            String itemField) throws ReflectiveOperationException {
        this.feature = feature;
        var dispatch = Reflect.method(container, "dispatchTouchEvent", boolean.class, MotionEvent.class);
        var childAt = Reflect.method(container, "findChildViewUnder", View.class, float.class, float.class);
        var blocked = Reflect.method(card, "getBlockViewState", boolean.class);
        cardItem = Reflect.field(card, itemField, item);
        cardKey = Reflect.field(item, "a", String.class);
        release = () -> {
            releasedKey = null;
            feature.changed();
        };
        dispatchHook = feature.module.hook(dispatch).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
            View list = (View) chain.getThisObject();
            MotionEvent event = (MotionEvent) chain.getArg(0);
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN) {
                if (press != null) {
                    press.resetTransform();
                    end();
                }
                if (feature.enabled() && feature.unlocked()) {
                    Object hit = Reflect.call(childAt, list, event.getX(), event.getY());
                    if (card.isInstance(hit)) {
                        View row = (View) hit;
                        String key = key(row);
                        if (key != null && feature.store.ready(key) && !slides.containsKey(row)
                                && !Boolean.TRUE.equals(Reflect.call(blocked, row)))
                            press = new Press(list, row, key, event);
                    }
                }
            }
            Press active = press;
            if (active == null || active.list != list) return chain.proceed();
            if (action == MotionEvent.ACTION_MOVE && active.route == Route.UNDECIDED) {
                float dx = event.getX() - active.x;
                float dy = event.getY() - active.y;
                if (Math.max(Math.abs(dx), Math.abs(dy)) > active.config.getScaledTouchSlop()) {
                    active.route = Route.NATIVE;
                    if (Math.abs(dx) * .57735f > Math.abs(dy)) {
                        int direction = dx < 0 ? 1 : -1;
                        if (feature.handlesExpandedSwipe(active.row, direction)) {
                            active.route = Route.PAGE;
                        } else if (feature.rules().swipeDismiss() && active.valid()
                                && active.row.getScaleX() >= .98f && active.row.getScaleY() >= .98f) {
                            MotionEvent cancel = MotionEvent.obtain(event);
                            cancel.setAction(MotionEvent.ACTION_CANCEL);
                            try { chain.proceed(new Object[]{cancel}); }
                            finally { cancel.recycle(); }
                            active.dismissSign = -direction;
                            active.members = feature.group(active.key).stream().map(member -> member.key())
                                    .collect(Collectors.toUnmodifiableSet());
                            active.route = Route.DISMISS;
                        }
                    }
                }
            }
            if (active.route == Route.DISMISS) {
                active.velocity.addMovement(event);
                if (action == MotionEvent.ACTION_MOVE && active.valid() && !active.cancelled) active.drag(event.getX());
                if (action == MotionEvent.ACTION_POINTER_DOWN) active.cancelled = true;
                if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                    if (action == MotionEvent.ACTION_UP && !active.cancelled && active.valid()) active.drag(event.getX());
                    active.velocity.computeCurrentVelocity(1000, active.config.getScaledMaximumFlingVelocity());
                    boolean clear = action == MotionEvent.ACTION_UP && !active.cancelled && active.valid()
                            && shouldDismiss(active.dx, active.velocity.getXVelocity(), active.row.getWidth(),
                            active.config.getScaledTouchSlop(), active.config.getScaledMinimumFlingVelocity());
                    end();
                    if (active.valid()) animate(active, clear); else active.resetTransform();
                }
                return true;
            }
            if (action == MotionEvent.ACTION_MOVE) active.velocity.addMovement(event);
            try { return chain.proceed(); }
            finally {
                if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) end();
            }
        });
    }

    static boolean shouldDismiss(float dx, float velocity, int width, int slop, int minimumVelocity) {
        return width > 0 && (Math.abs(dx) >= width * .35f
                || Math.abs(dx) >= Math.max(2 * slop, width * .12f) && dx * velocity > 0
                && Math.abs(velocity) >= 4 * minimumVelocity);
    }

    static float outwardDistance(float dx, int sign) {
        return Math.max(0f, dx * sign) * sign;
    }

    private String key(View view) {
        Object item = Reflect.get(cardItem, view);
        return item == null ? null : (String) Reflect.get(cardKey, item);
    }
    private void end() {
        Press prior = press;
        if (prior == null) return;
        press = null;
        prior.velocity.recycle();
        releasedKey = prior.key;
        feature.main.removeCallbacks(release);
        feature.main.postDelayed(release, AFTER_TOUCH_MS);
    }
    boolean holds(String key) {
        return key.equals(releasedKey) || press != null && key.equals(press.key)
                || slides.values().stream().anyMatch(slide -> key.equals(slide.press.key));
    }

    private void animate(Press active, boolean clear) {
        var slide = new Slide(active, clear);
        slides.put(active.row, slide);
        active.row.addOnAttachStateChangeListener(slide);
        slide.animator.start();
    }

    void close() {
        dispatchHook.unhook();
        if (press != null) press.resetTransform();
        end();
        feature.main.removeCallbacks(release);
        for (Slide slide : List.copyOf(slides.values())) slide.remove();
    }

    private final class Press {
        final View list, row;
        final String key;
        final float x, y, base;
        final ViewConfiguration config;
        final VelocityTracker velocity = VelocityTracker.obtain();
        Route route = Route.UNDECIDED;
        int dismissSign;
        Set<String> members = Set.of();
        float dx;
        boolean cancelled;

        Press(View list, View row, String key, MotionEvent event) {
            this.list = list;
            this.row = row;
            this.key = key;
            x = event.getX();
            y = event.getY();
            base = row.getTranslationX();
            config = ViewConfiguration.get(row.getContext());
            velocity.addMovement(event);
        }
        boolean valid() {
            return row.getParent() == list && row.isAttachedToWindow() && key.equals(key(row));
        }
        void drag(float current) {
            dx = outwardDistance(current - x, dismissSign);
            row.setTranslationX(base + dx);
        }
        void resetTransform() {
            if (route == Route.DISMISS && row.getTranslationX() == base + dx) row.setTranslationX(base);
        }
    }

    private final class Slide extends AnimatorListenerAdapter implements View.OnAttachStateChangeListener {
        final Press press;
        final ValueAnimator animator;
        final Set<String> clearing;
        final Runnable restore = this::remove;

        Slide(Press press, boolean clear) {
            this.press = press;
            clearing = clear ? press.members : Set.of();
            float target = clearing.isEmpty() ? press.base : press.dx < 0
                    ? -press.row.getRight() - 1f : press.list.getWidth() - press.row.getLeft() + 1f;
            animator = ValueAnimator.ofFloat(press.row.getTranslationX(), target);
            animator.setDuration(185);
            animator.setInterpolator(SETTLE);
            animator.addUpdateListener(frame -> {
                if (press.valid()) press.row.setTranslationX((float) frame.getAnimatedValue());
                else remove();
            });
            animator.addListener(this);
        }
        @Override public void onAnimationEnd(Animator animation) {
            if (slides.get(press.row) != this) return;
            if (clearing.isEmpty() || !press.valid() || !feature.enabled() || !feature.unlocked()
                    || !feature.rules().swipeDismiss()) {
                remove();
                return;
            }
            feature.dismissCard(press.key, clearing);
            // Keep the outgoing row outside the viewport until the OEM list removes it.
            feature.main.postDelayed(restore, 360);
        }
        void remove() {
            if (!slides.remove(press.row, this)) return;
            feature.main.removeCallbacks(restore);
            press.row.removeOnAttachStateChangeListener(this);
            animator.cancel();
            press.row.setTranslationX(press.base);
            feature.changed();
        }
        @Override public void onViewAttachedToWindow(View view) { }
        @Override public void onViewDetachedFromWindow(View view) { remove(); }
    }
}
