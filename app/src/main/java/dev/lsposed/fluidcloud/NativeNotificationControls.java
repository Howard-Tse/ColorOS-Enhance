package dev.lsposed.fluidcloud;

import android.view.View;
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
    private final Method timeout;
    private final Method cardShowing;
    private final Field cardSingleton;
    private final Method autoCardClose, autoCardTransition;
    private final Field cardState;
    private final NativeNotificationCardTouch cardTouch;
    private int menuGeneration;

    @SuppressWarnings("unchecked")
    NativeNotificationControls(NotificationCloudFeature feature, View root, NotificationPluginRepository binding) throws ReflectiveOperationException {
        this.feature = feature; ClassLoader loader = root.getClass().getClassLoader();
        Class<?> container = Reflect.type(loader, "com.oplus.systemui.plugins.seedling.capsule.ui.view.CapsuleContainer");
        Class<?> owner = Reflect.field(container, "n", Object.class).getType();
        Class<?> capsule = Reflect.type(loader, "com.oplus.systemui.plugins.seedling.capsule.data.model.a");
        Method update = Reflect.method(owner, "a", void.class, ArrayList.class);
        Method accepts = Reflect.method(owner, "H", boolean.class, capsule);
        Method state = Reflect.method(owner, "n", int.class, ArrayList.class);
        Method apply = Reflect.method(owner, "g", void.class, int.class, boolean.class);
        Method click = Reflect.method(owner, "p", void.class, String.class, boolean.class);
        Method expand = Reflect.method(owner, "w", void.class, String.class);
        Method normal = Reflect.method(owner, "v", void.class, owner, String.class);
        var fields = CapsuleFields.resolve(owner, Reflect.type(loader, "com.oplus.systemui.plugins.seedling.base.a"));
        Field candidateKey = Reflect.field(capsule, "a", String.class);
        Field controller = Reflect.field(capsule, "n", Reflect.type(loader, "com.oplus.systemui.plugins.seedling.capsule.data.model.d"));
        Class<?> repo = Reflect.type(loader, "com.oplus.systemui.plugins.seedling.capsule.data.repo.e");
        Class<?> wrapped = binding.wrapped();
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
        Class<?> cardContainer = Reflect.type(loader, "com.oplus.systemui.plugins.seedling.card.ui.view.CardContainer");
        Class<?> card = Reflect.field(cardContainer, "b", Object.class).getType();
        NativeNotificationCards capacity = new NativeNotificationCards(loader, card);
        cardSingleton = Reflect.uniqueField(card, field -> java.lang.reflect.Modifier.isStatic(field.getModifiers()) && field.getType() == card);
        timeout = Reflect.method(card, "k", boolean.class, String.class);
        cardShowing = Reflect.method(card, "m", boolean.class);
        Method close = null, transition = null;
        Field currentState = null;
        try {
            String cardPackage = "com.oplus.systemui.plugins.seedling.card.";
            Class<?> cardItem = Reflect.type(loader, cardPackage + "ui.model.o");
            currentState = Reflect.uniqueField(card, f -> Reflect.instance(f) && f.getType() == String.class);
            transition = card.getDeclaredMethod("j", card, boolean.class, cardItem, int.class);
            if (!java.lang.reflect.Modifier.isStatic(transition.getModifiers())) throw new NoSuchMethodException("card transition");
            transition.setAccessible(true);
            close = Reflect.method(card, "e", boolean.class, String.class, transition.getReturnType());
        } catch (ReflectiveOperationException unavailable) { /* The automatic card still expires in ColorOS. */ }
        autoCardClose = close;
        autoCardTransition = transition;
        cardState = currentState;
        Class<?> interactor = binding.singleton().getType();
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
                Object instance = chain.getThisObject();
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
                    Object nativeAction = Reflect.get(fields.action(), instance);
                    if (nativeView != null) Reflect.call(callback, nativeView, nativeAction == null ? null
                            : handler.newInstance(Reflect.get(repoOwner, control), base, nativeAction, 2));
                }
                fields.list().set(instance, next);
                Reflect.call(apply, instance, Reflect.call(state, instance, next), fields.force().getBoolean(instance) && !fields.blocked().getBoolean(instance));
                fields.force().setBoolean(instance, false); return null;
            }));
            hooks.add(feature.module.hook(click).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
                String selected = (String) chain.getArg(0);
                if (!feature.enabled() || !feature.unlocked() || feature.store.get(selected) == null) return chain.proceed();
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
            Class<?> actionEvent = Reflect.type(loader, "com.heytap.log.strategy.f");
            Class<?> clickEvent = Reflect.type(loader, "com.oplus.systemui.plugins.seedling.card.ui.model.c");
            Class<?> cardItem = Reflect.type(loader, "com.oplus.systemui.plugins.seedling.card.ui.model.o");
            Field clickedItem = Reflect.uniqueField(clickEvent, f -> Reflect.instance(f) && f.getType() == cardItem);
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
    record CapsuleFields(Field list, Field force, Field blocked, Field action) {
        static CapsuleFields resolve(Class<?> owner, Class<?> handler) throws NoSuchFieldException {
            try { return bind(owner, handler, "g", "y", "j", "E"); }
            catch (NoSuchFieldException oldLayoutAbsent) { return bind(owner, handler, "h", "z", "k", "F"); }
        }

        private static CapsuleFields bind(Class<?> owner, Class<?> handler, String list, String force,
                                          String blocked, String action) throws NoSuchFieldException {
            return new CapsuleFields(Reflect.field(owner, list, ArrayList.class), Reflect.field(owner, force, boolean.class),
                    Reflect.field(owner, blocked, boolean.class), Reflect.field(owner, action, handler));
        }
    }

    boolean holds(String selected) { return cardTouch != null && cardTouch.holds(selected); }
    @SuppressWarnings({"unchecked", "rawtypes"}) private static Object enumValue(Class<?> type, String name) { return Enum.valueOf((Class) type, name); }
    void closeCard(Runnable continuation) {
        int generation = ++menuGeneration;
        Object card = Reflect.get(cardSingleton, null);
        if (card != null && Boolean.TRUE.equals(Reflect.call(cardShowing, card))) {
            boolean closed = Boolean.TRUE.equals(Reflect.call(timeout, card, "EVENT_CARD_TOUCH_OUT"));
            if (!closed && autoCardClose != null && "CARD_STATE_AUTO".equals(Reflect.get(cardState, card))) {
                // An explicit menu action may leave the automatic card before its native timer ends.
                Object transition = Reflect.call(autoCardTransition, null, card, false, null, 15);
                Reflect.call(autoCardClose, card, "EVENT_CARD_TOUCH_OUT", transition);
            }
        }
        afterCardClosed(generation, continuation, 0);
    }
    private void afterCardClosed(int generation, Runnable continuation, int attempt) {
        if (generation != menuGeneration || !feature.enabled() || !feature.unlocked()) return;
        Object card = Reflect.get(cardSingleton, null);
        if (card == null || !Boolean.TRUE.equals(Reflect.call(cardShowing, card))) { continuation.run(); return; }
        if (attempt < 40) feature.main.postDelayed(() -> afterCardClosed(generation, continuation, attempt + 1), 20);
    }
    void close() {
        menuGeneration++;
        if (cardTouch != null) cardTouch.close();
        for (var hook : hooks) hook.unhook();
        hooks.clear();
    }
}
