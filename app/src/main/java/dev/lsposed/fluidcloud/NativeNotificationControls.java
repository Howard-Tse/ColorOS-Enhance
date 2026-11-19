package dev.lsposed.fluidcloud;

import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import dev.howard.colorosenhance.notifications.NotificationCloudRules;
import io.github.libxposed.api.XposedInterface;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Notification-owned input/candidate policy. Existing OEM candidates keep their native ordering and views. */
final class NativeNotificationControls {
    private final NotificationCloudFeature feature;
    private final ArrayList<XposedInterface.HookHandle> hooks = new ArrayList<>();
    private final Method select, expand, timeout;
    private final Method cardShowing;
    private final Field cardSingleton;
    private final NativeNotificationCardTouch cardTouch;
    private final List<java.lang.ref.WeakReference<Object>> owners = new ArrayList<>();
    private int popupGeneration;
    private int menuGeneration;
    private long lastPopup, lastTouch;

    @SuppressWarnings("unchecked")
    NativeNotificationControls(NotificationCloudFeature feature, View root, Class<?> model) throws ReflectiveOperationException {
        this.feature = feature; ClassLoader loader = root.getClass().getClassLoader();
        Class<?> owner = Reflect.type(loader, "com.oplus.systemui.plugins.seedling.capsule.ui.viewmodel.A");
        Class<?> capsule = Reflect.type(loader, "com.oplus.systemui.plugins.seedling.capsule.data.model.a");
        Method update = Reflect.method(owner, "a", void.class, ArrayList.class);
        Method accepts = Reflect.method(owner, "H", boolean.class, capsule);
        Method state = Reflect.method(owner, "n", int.class, ArrayList.class);
        Method apply = Reflect.method(owner, "g", void.class, int.class, boolean.class);
        Method click = Reflect.method(owner, "p", void.class, String.class, boolean.class);
        select = Reflect.method(owner, "G", void.class, String.class, String.class, boolean.class, boolean.class);
        expand = Reflect.method(owner, "w", void.class, String.class);
        Method normal = Reflect.method(owner, "v", void.class, owner, String.class);
        Field list = Reflect.field(owner, "g", ArrayList.class), force = Reflect.field(owner, "y", boolean.class), blocked = Reflect.field(owner, "j", boolean.class);
        Field candidateKey = Reflect.field(capsule, "a", String.class);
        Field action = Reflect.field(owner, "E", Reflect.type(loader, "com.oplus.systemui.plugins.seedling.capsule.ui.viewmodel.n"));
        Field controller = Reflect.field(capsule, "n", Reflect.type(loader, "com.oplus.systemui.plugins.seedling.capsule.data.model.d"));
        Class<?> repo = Reflect.type(loader, "com.oplus.systemui.plugins.seedling.capsule.data.repo.e");
        Class<?> wrapped = Reflect.type(loader, "com.oplus.systemui.plugins.seedling.data.interactor.m");
        Field wrappedModel = Reflect.field(repo, "a", wrapped);
        Field repoOwner = Reflect.field(repo, "b", Reflect.type(loader, "com.oplus.systemui.plugins.seedling.capsule.data.repo.h"));
        Class<?> size = Reflect.type(loader, "com.oplus.systemui.plugins.livealert.lib.ViewSize");
        Object small = enumValue(size, "VIEW_SIZE_SM");
        Class<?> wrapper = Reflect.type(loader, "com.oplus.systemui.plugins.livealert.lib.h");
        Method view = Reflect.method(wrapped, "E", wrapper, size);
        Method callback = Reflect.method(wrapper, "m", void.class, Reflect.type(loader, "com.oplus.systemui.plugins.livealert.lib.i"));
        Constructor<?> handler = Reflect.type(loader, "com.heytap.log.appender.a").getDeclaredConstructor(Object.class, Object.class, Object.class, int.class);
        Object priority = Reflect.type(loader, "com.oplus.systemui.plugins.seedling.aod.a").getDeclaredConstructor(int.class).newInstance(1);
        Constructor<?> tie = Reflect.type(loader, "com.heytap.log.uploader.b").getDeclaredConstructor(int.class, Object.class);
        Comparator<Object> comparator = (Comparator<Object>) tie.newInstance(4, tie.newInstance(3, priority));
        Class<?> card = Reflect.type(loader, "com.oplus.systemui.plugins.seedling.card.ui.viewmodel.i");
        NativeNotificationCards capacity = new NativeNotificationCards(loader, card);
        cardSingleton = Reflect.uniqueField(card, field -> java.lang.reflect.Modifier.isStatic(field.getModifiers()) && field.getType() == card);
        timeout = Reflect.method(card, "k", boolean.class, String.class);
        cardShowing = Reflect.method(card, "m", boolean.class);
        Class<?> interactor = Reflect.type(loader, "com.oplus.systemui.plugins.seedling.data.interactor.l");
        Method dismiss = Reflect.method(interactor, "b", void.class, wrapped, boolean.class);
        Method key = Reflect.method(wrapped, "getKey", String.class);
        try {
            hooks.add(feature.module.hook(capacity.filter).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
                List<?> data = (List<?>) chain.getArg(0);
                if (data.size() <= 4 || !capacity.contains(feature, data)
                        || !Boolean.TRUE.equals(Reflect.call(capacity.manual, chain.getThisObject()))) return chain.proceed();
                // Preserve the firmware's own eligibility checks in each bounded batch.
                var selected = new ArrayList<Object>();
                for (int start = 0; start < data.size(); start += 4)
                    selected.addAll((List<?>) chain.proceed(new Object[]{data.subList(start, Math.min(start + 4, data.size()))}));
                return selected;
            }));
            hooks.add(feature.module.hook(capacity.present).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
                List<?> data = (List<?>) chain.getArg(0);
                if (data.size() <= 4 || !capacity.contains(feature, data)) return chain.proceed();
                return capacity.present(chain.getThisObject(), data);
            }));
            hooks.add(feature.module.hook(update).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
                Object instance = chain.getThisObject(); remember(instance);
                List<?> input = (List<?>) chain.getArg(0);
                if (!feature.enabled() || feature.store.entries().isEmpty() || input.size() <= 4) return chain.proceed();
                boolean converted = false;
                for (Object item : input) if (feature.store.get((String) Reflect.get(candidateKey, item)) != null) { converted = true; break; }
                if (!converted) return chain.proceed();
                ArrayList<Object> next = new ArrayList<>();
                for (Object item : input) if (Boolean.TRUE.equals(Reflect.call(accepts, instance, item))) next.add(item);
                next.sort(comparator); // Same comparator as OEM, without the four-item truncation.
                for (Object item : next) {
                    Object control = Reflect.get(controller, item);
                    if (!repo.isInstance(control)) throw new IllegalStateException("Unexpected native capsule view controller");
                    Object base = Reflect.get(wrappedModel, control), nativeView = Reflect.call(view, base, small);
                    Object nativeAction = Reflect.get(action, instance);
                    if (nativeView != null) Reflect.call(callback, nativeView, nativeAction == null ? null
                            : handler.newInstance(Reflect.get(repoOwner, control), base, nativeAction, 2));
                }
                list.set(instance, next);
                Reflect.call(apply, instance, Reflect.call(state, instance, next), force.getBoolean(instance) && !blocked.getBoolean(instance));
                force.setBoolean(instance, false); return null;
            }));
            hooks.add(feature.module.hook(click).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
                String selected = (String) chain.getArg(0); lastTouch = SystemClock.uptimeMillis(); popupGeneration++;
                if (!feature.enabled() || feature.store.get(selected) == null) return chain.proceed();
                if (feature.rules().directOpen() && feature.store.get(selected).value().getNotification().contentIntent != null) {
                    feature.armGroupOpen(selected);
                    Reflect.call(normal, null, chain.getThisObject(), selected);
                } else Reflect.call(expand, chain.getThisObject(), selected);
                return null;
            }));
            hooks.add(feature.module.hook(dismiss).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
                String selected = (String) Reflect.call(key, chain.getArg(0));
                if (!feature.enabled() || feature.store.get(selected) == null) return chain.proceed();
                // The synthetic model is owned by the active Android notification, not the OEM timeout.
                return null;
            }));
            Class<?> container = Reflect.type(loader, "com.oplus.systemui.plugins.seedling.card.ui.view.CardContainer");
            for (Method method : container.getDeclaredMethods()) if ((method.getName().equals("onTouchEvent") || method.getName().equals("onInterceptTouchEvent"))
                    && java.util.Arrays.equals(method.getParameterTypes(), new Class<?>[]{MotionEvent.class})) hooks.add(feature.module.hook(method)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
                        if (((MotionEvent) chain.getArg(0)).getActionMasked() == MotionEvent.ACTION_DOWN) { lastTouch = SystemClock.uptimeMillis(); popupGeneration++; }
                        return chain.proceed();
                    }));
            Class<?> actionEvent = Reflect.type(loader, "com.heytap.log.strategy.f");
            Class<?> clickEvent = Reflect.type(loader, "com.oplus.systemui.plugins.seedling.card.ui.model.c");
            Class<?> cardItem = Reflect.type(loader, "com.oplus.systemui.plugins.seedling.card.ui.model.o");
            Field clickedItem = Reflect.field(clickEvent, "d", cardItem);
            Field cardKey = Reflect.field(cardItem, "a", String.class);
            Method dispatch = Reflect.method(card, "h", void.class, actionEvent);
            hooks.add(feature.module.hook(dispatch).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
                Object event = chain.getArg(0);
                if (!feature.enabled() || !feature.unlocked() || !clickEvent.isInstance(event)) return chain.proceed();
                Object item = Reflect.get(clickedItem, event);
                String selected = (String) Reflect.get(cardKey, item);
                if (!feature.store.ready(selected)) return chain.proceed();
                feature.armGroupOpen(selected);
                return chain.proceed();
            }));
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) { close(); throw failure; }
        NativeNotificationCardTouch touch;
        try { touch = new NativeNotificationCardTouch(feature, loader); }
        catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
            feature.failure("Native notification card swipe callbacks unavailable", failure);
            touch = null;
        }
        cardTouch = touch;
    }
    boolean holds(String selected) { return cardTouch != null && cardTouch.holds(selected); }
    @SuppressWarnings({"unchecked", "rawtypes"}) private static Object enumValue(Class<?> type, String name) { return Enum.valueOf((Class) type, name); }
    private void remember(Object owner) {
        owners.removeIf(reference -> reference.get() == null);
        for (var reference : owners) if (reference.get() == owner) return;
        owners.add(new java.lang.ref.WeakReference<>(owner));
    }
    void popup(String key) {
        long now = SystemClock.uptimeMillis();
        if (now - lastPopup < 3000 || now - lastTouch < 1000 || !feature.unlocked()) return;
        int generation = ++popupGeneration;
        feature.main.postDelayed(() -> {
            if (generation != popupGeneration || !feature.enabled() || !feature.unlocked() || feature.store.get(key) == null) return;
            Object currentCard = Reflect.get(cardSingleton, null);
            if (currentCard != null && Boolean.TRUE.equals(Reflect.call(cardShowing, currentCard))) return;
            for (var reference : owners) {
                Object owner = reference.get(); if (owner == null) continue;
                try {
                    Object model = feature.store.get(key).value();
                    String service = "laid_" + ((android.service.notification.StatusBarNotification) model).getPackageName();
                    Reflect.call(select, owner, key, service, true, true); Reflect.call(expand, owner, key);
                    lastPopup = SystemClock.uptimeMillis();
                    int seconds = feature.module.cloudValues().get("notification_popup_seconds") instanceof Number value ? Math.max(1, Math.min(15, value.intValue())) : 3;
                    feature.main.postDelayed(() -> {
                        if (generation != popupGeneration || !feature.unlocked()) return;
                        Object card = Reflect.get(cardSingleton, null); if (card != null) Reflect.call(timeout, card, "EVENT_CARD_TIME_OUT");
                    }, seconds * 1000L); break;
                } catch (RuntimeException | LinkageError failure) { feature.failure("Notification automatic expansion unavailable", failure); }
            }
        }, 250);
    }
    void closeCard(Runnable continuation) {
        popupGeneration++;
        int generation = ++menuGeneration;
        Object card = Reflect.get(cardSingleton, null);
        if (card != null && Boolean.TRUE.equals(Reflect.call(cardShowing, card))) Reflect.call(timeout, card, "EVENT_CARD_TOUCH_OUT");
        afterCardClosed(generation, continuation, 0);
    }
    private void afterCardClosed(int generation, Runnable continuation, int attempt) {
        if (generation != menuGeneration || !feature.enabled() || !feature.unlocked()) return;
        Object card = Reflect.get(cardSingleton, null);
        if (card == null || !Boolean.TRUE.equals(Reflect.call(cardShowing, card))) { continuation.run(); return; }
        if (attempt < 40) feature.main.postDelayed(() -> afterCardClosed(generation, continuation, attempt + 1), 20);
    }
    void close() {
        popupGeneration++; menuGeneration++;
        if (cardTouch != null) cardTouch.close();
        for (var hook : hooks) hook.unhook();
        hooks.clear(); owners.clear();
    }
}
