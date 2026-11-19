package dev.lsposed.fluidcloud;

import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import io.github.libxposed.api.XposedInterface;
import java.util.ArrayList;
import java.util.List;

/** Retain the card's current action views while a touch is in flight. */
final class NativeNotificationCardTouch {
    private static final long AFTER_TOUCH_MS = 160;
    private final NotificationCloudFeature feature;
    private final List<XposedInterface.HookHandle> hooks = new ArrayList<>();
    private Object owner;
    private String key;
    private boolean swipe;
    private long releasedAt;
    private final Runnable release;

    NativeNotificationCardTouch(NotificationCloudFeature feature, ClassLoader loader) throws ReflectiveOperationException {
        this.feature = feature;
        release = () -> {
            owner = null;
            key = null;
            swipe = false;
            feature.changed();
        };
        String base = "com.oplus.systemui.plugins.seedling.card.ui.";
        Class<?> container = Reflect.type(loader, base + "view.CardContainer");
        Class<?> card = Reflect.type(loader, base + "view.CardView");
        Class<?> item = Reflect.type(loader, base + "model.o");
        Class<?> callbacks = Reflect.type(loader, base + "view.c");
        var dispatch = Reflect.method(container, "dispatchTouchEvent", boolean.class, MotionEvent.class);
        var childAt = Reflect.method(container, "findChildViewUnder", View.class, float.class, float.class);
        var cardItem = Reflect.field(card, "h", item);
        var cardKey = Reflect.field(item, "a", String.class);
        // Both directions are delivered by the same stock touch helper once its swipe threshold is met.
        var left = Reflect.method(callbacks, "s", void.class, View.class);
        var right = Reflect.method(callbacks, "w", void.class, View.class);
        try {
            hooks.add(feature.module.hook(dispatch).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
                Object view = chain.getThisObject();
                MotionEvent event = (MotionEvent) chain.getArg(0);
                int action = event.getActionMasked();
                if (action == MotionEvent.ACTION_DOWN) {
                    finish();
                    if (feature.enabled() && feature.unlocked()) {
                        Object child = Reflect.call(childAt, view, event.getX(), event.getY());
                        Object model = card.isInstance(child) ? Reflect.get(cardItem, child) : null;
                        String selected = model == null ? null : (String) Reflect.get(cardKey, model);
                        if (selected != null && feature.store.ready(selected)) {
                            feature.main.removeCallbacks(release);
                            owner = view;
                            key = selected;
                        }
                    }
                }
                if (owner != view) return chain.proceed();
                if (action == MotionEvent.ACTION_UP && swipe) {
                    MotionEvent cancel = MotionEvent.obtain(event);
                    cancel.setAction(MotionEvent.ACTION_CANCEL);
                    try { chain.proceed(new Object[]{cancel}); }
                    finally { cancel.recycle(); }
                    String selected = key;
                    finish();
                    feature.dismissCard(selected);
                    return true;
                }
                try { return chain.proceed(); }
                finally {
                    if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) finish();
                }
            }));
            for (var gesture : List.of(left, right))
                hooks.add(feature.module.hook(gesture).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
                    if (chain.getArg(0) == owner && key != null) swipe = true;
                    return chain.proceed();
                }));
        } catch (RuntimeException | LinkageError unavailable) {
            close();
            throw unavailable;
        }
    }

    private void finish() {
        if (owner == null) return;
        owner = null;
        swipe = false;
        releasedAt = SystemClock.uptimeMillis();
        feature.main.removeCallbacks(release);
        feature.main.postDelayed(release, AFTER_TOUCH_MS);
    }

    boolean holds(String selected) {
        return selected.equals(key) && (owner instanceof View view && view.isAttachedToWindow()
                || SystemClock.uptimeMillis() - releasedAt < AFTER_TOUCH_MS);
    }
    void close() {
        feature.main.removeCallbacks(release);
        hooks.forEach(XposedInterface.HookHandle::unhook);
        hooks.clear();
    }
}
