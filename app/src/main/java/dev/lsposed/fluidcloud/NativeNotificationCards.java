package dev.lsposed.fluidcloud;

import android.view.View;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/** The OEM RecyclerView remains the renderer; only its four-card presentation cap is removed. */
final class NativeNotificationCards {
    final Method filter, present, manual;
    private final Method showing, refresh, view, deliver, reapply;
    private final Field current, state, listener, blocked, key, service, creator, actions, lifecycle, reminder, shape, config, configShape;
    private final Constructor<?> item, ui, refreshAction;

    NativeNotificationCards(ClassLoader loader, Class<?> owner) throws ReflectiveOperationException {
        filter = Reflect.method(owner, "i", ArrayList.class, List.class);
        present = Reflect.method(owner, "r", int.class, List.class);
        manual = Reflect.method(owner, "n", boolean.class);
        showing = Reflect.method(owner, "m", boolean.class);
        refresh = Reflect.method(owner, "o", void.class, boolean.class);
        current = Reflect.field(owner, "f", Object.class);
        Class<?> uiType = Reflect.type(loader, "com.oplus.systemui.plugins.seedling.card.ui.model.k");
        state = Reflect.field(owner, "g", uiType);
        Class<?> callback = Reflect.type(loader, "com.oplus.systemui.plugins.seedling.card.ui.view.c");
        listener = Reflect.field(owner, "m", callback); deliver = Reflect.method(callback, "a", void.class, uiType);
        blocked = Reflect.field(owner, "i", boolean.class);
        Class<?> model = Reflect.type(loader, "com.oplus.systemui.plugins.seedling.card.data.model.a");
        key = Reflect.field(model, "a", String.class); service = Reflect.field(model, "x", String.class);
        creator = Reflect.field(model, "i", Object.class); actions = Reflect.field(model, "j", Object.class);
        lifecycle = Reflect.field(model, "l", Object.class); reminder = Reflect.field(model, "d", boolean.class);
        shape = Reflect.field(model, "C", int.class); config = Reflect.field(model, "s", Object.class);
        configShape = Reflect.field(config.getType(), "n", int.class);
        view = Reflect.method(creator.getType(), "e", View.class);
        Class<?> itemType = Reflect.type(loader, "com.oplus.systemui.plugins.seedling.card.ui.model.o");
        item = itemType.getDeclaredConstructor(String.class, String.class, View.class, actions.getType(), lifecycle.getType(), boolean.class);
        ui = uiType.getDeclaredConstructor(List.class, boolean.class);
        Class<?> action = Reflect.type(loader, "com.oplus.systemui.livealert.classic.origin.a");
        refreshAction = action.getDeclaredConstructor(int.class, Object.class);
        reapply = Reflect.method(action, "invoke", Object.class);
    }
    boolean contains(NotificationCloudFeature feature, List<?> models) {
        if (!feature.enabled()) return false;
        for (Object model : models) if (feature.store.get((String) Reflect.get(key, model)) != null) return true;
        return false;
    }
    int present(Object owner, List<?> models) throws ReflectiveOperationException {
        var visible = new ArrayList<Object>(); var views = new ArrayList<Object>();
        for (Object model : models) {
            View content = (View) Reflect.call(view, Reflect.get(creator, model));
            if (content == null) continue;
            Object appearance = Reflect.get(config, model);
            views.add(item.newInstance(Reflect.get(key, model), Reflect.get(service, model), content,
                    Reflect.get(actions, model), Reflect.get(lifecycle, model),
                    shape.getInt(model) == 3 || appearance != null && configShape.getInt(appearance) != 0));
            visible.add(model);
        }
        List<?> previous = Reflect.get(current, owner) instanceof List<?> old ? old : List.of();
        boolean changed = !Boolean.TRUE.equals(Reflect.call(showing, owner))
                || !keys(previous).equals(keys(visible));
        boolean nextReminder = hasReminder(visible), reminderChanged = hasReminder(previous) != nextReminder;
        current.set(owner, visible);
        if (!changed && !reminderChanged) return 3;
        Object next = ui.newInstance(views, nextReminder); state.set(owner, next);
        if (changed) Reflect.call(refresh, owner, blocked.getBoolean(owner));
        Object callback = Reflect.get(listener, owner);
        if (callback != null) Reflect.call(deliver, callback, next);
        if (changed) return 4;
        if (Boolean.TRUE.equals(Reflect.call(showing, owner))) Reflect.call(reapply, refreshAction.newInstance(7, owner));
        return 5;
    }
    private List<String> keys(List<?> models) {
        return models.stream().map(model -> (String) Reflect.get(key, model)).toList();
    }
    private boolean hasReminder(List<?> models) {
        for (Object model : models) if (Boolean.TRUE.equals(Reflect.get(reminder, model))) return true;
        return false;
    }
}
