package dev.lsposed.fluidcloud;

import android.app.PendingIntent;
import android.content.Context;
import android.os.Bundle;
import android.service.notification.StatusBarNotification;
import android.view.View;
import android.view.ViewGroup;
import dev.howard.colorosenhance.notifications.NotificationCloudRules;
import dev.howard.colorosenhance.notifications.NotificationCloudStore;
import io.github.libxposed.api.XposedInterface;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.json.JSONException;
import org.json.JSONObject;

/** Feeds the older built-in notification templates through the two native entry listeners. */
final class LegacyNotificationCloud implements NotificationCloudRenderer {
    private record Projection(Object capsule, Object shade, NotificationCloudGrouping.RenderVersion version,
                              List<NotificationCloudStore.Entry<StatusBarNotification>> members) { }

    private final ClassLoader loader;
    private final Context host;
    private final NotificationCloudFeature feature;
    private final Object statusEntry, shadeEntry, statusListener, shadeListener;
    private final Constructor<?> model;
    private final Field source;
    private final Method changed, prepare, destroy, wrappers, ready, view, closeCard;
    private final List<XposedInterface.HookHandle> hooks = new ArrayList<>();
    private volatile Map<String, Projection> projections = Map.of();
    private final Runnable poll = this::poll;
    private int remainingPolls;
    private NotificationCloudRules previousRules;
    private final Field cardOwner;
    private final Method viewItem;
    private final Field viewKey;

    LegacyNotificationCloud(NotificationCloudFeature feature, ClassLoader loader, Context host)
            throws ReflectiveOperationException {
        this.feature = feature;
        this.loader = loader;
        this.host = host;
        Class<?> area = Reflect.type(loader, "x3.a");
        statusEntry = ColorOs16Plugin.state(area, "ENTRY_STATUS_BAR");
        shadeEntry = ColorOs16Plugin.state(area, "ENTRY_NOTIFICATION");
        Class<?> repository = Reflect.type(loader, "a5.h");
        Object state = Reflect.get(Reflect.field(repository, "n", repository), null);
        if (state == null) throw new NoSuchFieldException("Legacy Seedling repository");
        Class<?> listener = Reflect.type(loader, "a5.c");
        var listenerFactory = listener.getDeclaredConstructor(repository, area);
        statusListener = listenerFactory.newInstance(state, statusEntry);
        shadeListener = listenerFactory.newInstance(state, shadeEntry);
        changed = Reflect.method(listener, "b", void.class, List.class);
        Class<?> type = Reflect.type(loader, "k3.b");
        model = type.getDeclaredConstructor(StatusBarNotification.class, area);
        source = Reflect.field(type, "a", StatusBarNotification.class);
        prepare = Reflect.method(type, "A", void.class);
        destroy = Reflect.method(type, "y", void.class);
        wrappers = Reflect.method(type, "j", List.class);
        ready = Reflect.method(type, "m", boolean.class);
        view = Reflect.method(Reflect.type(loader, "k3.c"), "getView", View.class);
        Class<?> card = Reflect.type(loader, "w4.j");
        cardOwner = Reflect.uniqueField(card, field -> !Reflect.instance(field) && field.getType() == card);
        closeCard = Reflect.method(card, "l", boolean.class, String.class);
        Class<?> capsule = Reflect.type(loader, ColorOs16Plugin.CAPSULE_MODEL);
        Method click = Reflect.method(capsule, "p", void.class, String.class, boolean.class);
        Method open = Reflect.method(capsule, "v", void.class, String.class);
        Method expand = Reflect.method(capsule, "w", void.class, String.class);
        Class<?> base = Reflect.type(loader, "x3.o");
        Class<?> wrapped = Reflect.type(loader, "a5.i");
        Field wrappedSource = Reflect.field(wrapped, "a", base);
        Method key = Reflect.method(base, "getKey", String.class);
        Method launch = Reflect.method(Reflect.type(loader, "i5.e"), "a", boolean.class,
                base, View.class, List.class, PendingIntent.class, Bundle.class);
        Method dismiss = Reflect.method(repository, "b", void.class, wrapped, boolean.class);
        Method duration = Reflect.method(wrapped, "I", long.class);
        Class<?> cardItem = Reflect.type(loader, "t4.o");
        Field cardKey = Reflect.field(cardItem, "a", String.class);
        Method swipe = Reflect.method(card, "m", void.class, cardItem);
        Class<?> capsuleView = Reflect.type(loader, NativeCapsuleAppearance.VIEWS + "CapsuleView");
        Class<?> capsuleItem = Reflect.type(loader, "k4.g");
        viewItem = Reflect.method(capsuleView, "getViewItem", capsuleItem);
        viewKey = Reflect.field(capsuleItem, "a", String.class);
        Method measure = Reflect.method(capsuleView, "onMeasure", void.class, int.class, int.class);
        Method width = Reflect.method(capsuleView, "setCapsuleDrawableWidth", void.class, int.class);
        try {
            hooks.add(feature.module.hook(click).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
                String selected = (String) chain.getArg(0);
                if (!feature.enabled() || !feature.unlocked() || !feature.store.ready(selected)) return chain.proceed();
                boolean openDirectly = feature.rules().directOpen()
                        && feature.store.get(selected).value().getNotification().contentIntent != null;
                if (openDirectly) feature.armGroupOpen(selected);
                Reflect.call(openDirectly ? open : expand, chain.getThisObject(), selected);
                return null;
            }));
            hooks.add(feature.module.hook(launch).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
                if (feature.enabled() && feature.rules().shadeCards() && chain.getArg(3) instanceof PendingIntent target)
                    feature.armGroupOpen((String) Reflect.call(key, chain.getArg(0)), target);
                return chain.proceed();
            }));
            hooks.add(feature.module.hook(dismiss).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
                Object item = chain.getArg(0);
                Object nativeModel = Reflect.get(wrappedSource, item);
                Projection owner = projections.get(Reflect.call(key, item));
                return owner != null && (owner.capsule() == nativeModel || owner.shade() == nativeModel) ? null : chain.proceed();
            }));
            hooks.add(feature.module.hook(duration).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
                Object result = chain.proceed();
                if (!Long.valueOf(3000L).equals(result)) return result;
                Object item = chain.getThisObject();
                Projection owner = projections.get(Reflect.call(key, item));
                return owner != null && owner.capsule() == Reflect.get(wrappedSource, item)
                        ? NativeNotificationReminders.durationMillis(feature.module.cloudValues().get("notification_popup_seconds"))
                        : result;
            }));
            hooks.add(feature.module.hook(swipe).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
                Object result = chain.proceed();
                String selected = (String) Reflect.get(cardKey, chain.getArg(0));
                if (projections.containsKey(selected)) feature.dismissCard(selected);
                return result;
            }));
            hooks.add(feature.module.hook(measure).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
                int limit = capsuleWidthLimit((View) chain.getThisObject());
                if (limit == Integer.MAX_VALUE) return chain.proceed();
                int spec = (int) chain.getArg(0);
                if (View.MeasureSpec.getMode(spec) != View.MeasureSpec.UNSPECIFIED)
                    limit = Math.min(limit, View.MeasureSpec.getSize(spec));
                return chain.proceed(new Object[] {View.MeasureSpec.makeMeasureSpec(limit, View.MeasureSpec.AT_MOST),
                        chain.getArg(1)});
            }));
            hooks.add(feature.module.hook(width).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
                int requested = (int) chain.getArg(0);
                int bounded = Math.min(requested, capsuleWidthLimit((View) chain.getThisObject()));
                return requested == bounded ? chain.proceed() : chain.proceed(new Object[] {bounded});
            }));
        } catch (RuntimeException | LinkageError failure) {
            hooks.forEach(XposedInterface.HookHandle::unhook);
            throw failure;
        }
        try { installShadeDismissal(host.getClassLoader()); }
        catch (ReflectiveOperationException | RuntimeException | LinkageError unavailable) {
            feature.failure("Legacy grouped shade-card clearing unavailable; native row clearing retained", unavailable);
        }
    }

    private void installShadeDismissal(ClassLoader system) throws ReflectiveOperationException {
        String prefix = "com.oplus.systemui.statusbar.notification.customcard.";
        Class<?> row = Reflect.type(system, prefix + "OplusCustomRow");
        Class<?> entry = Reflect.type(system, "com.android.systemui.plugins.custom.CustomRowEntry");
        Method getEntry = Reflect.method(row, "getEntry", entry);
        Method getKey = Reflect.method(entry, "getKey", String.class);
        Method dismiss = Reflect.method(Reflect.type(system, prefix + "OplusCustomRowControllerManager"),
                "dissmissCustomRow", void.class, row, String.class);
        hooks.add(feature.module.hook(dismiss).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
            Object current = Reflect.call(getEntry, chain.getArg(0));
            Projection projection = current == null ? null : projections.get(Reflect.call(getKey, current));
            Object result = chain.proceed();
            if (projection != null && projection.shade() != null && projection.members().size() > 1
                    && projection.members().stream().allMatch(item -> item.value().isClearable()))
                feature.main.post(() -> NativeShadeNotificationDismissal.dismissOtherMembers(feature, projection.members()));
            return result;
        }));
    }

    @Override public ClassLoader loader() { return loader; }
    @Override public boolean available() { return true; }
    @Override public int capsuleWidthLimit(View view) {
        int maximum = feature.capsuleMaxDp();
        if (maximum <= 0 || !feature.enabled() || !viewItem.getDeclaringClass().isInstance(view)) return Integer.MAX_VALUE;
        Object shown = Reflect.call(viewItem, view);
        if (shown == null || !projections.containsKey(Reflect.get(viewKey, shown))) return Integer.MAX_VALUE;
        return Math.round(Math.max(48, maximum) * view.getResources().getDisplayMetrics().density);
    }
    @Override public boolean open(String key) { return NativeNotificationCloud.openWithActivityStarter(feature, host, key); }
    @Override public void closeCard(Runnable continuation) {
        Object owner = Reflect.get(cardOwner, null);
        if (owner != null && Boolean.TRUE.equals(Reflect.call(closeCard, owner, "EVENT_CARD_TOUCH_OUT")))
            feature.main.postDelayed(continuation, 320L);
        else continuation.run();
    }

    @Override public void update(List<NotificationCloudStore.Entry<StatusBarNotification>> notifications,
            NotificationCloudRules rules, Set<String> autoExpand) {
        var next = new LinkedHashMap<String, Projection>();
        var retired = new ArrayList<Object>();
        boolean listChanged = false, shadeChanged = false;
        for (var group : NotificationCloudGrouping.groups(notifications, rules.groupApps())) {
            String key = group.getFirst().key();
            Projection previous = projections.get(key);
            var version = NotificationCloudGrouping.renderVersion(group);
            boolean popup = autoExpand.contains(group.getLast().key());
            if (previous != null && version.equals(previous.version()) && rules.equals(previousRules) && !popup) {
                next.put(key, new Projection(previous.capsule(), previous.shade(), version, group));
                continue;
            }
            try {
                StatusBarNotification render = feature.renderNotification(group);
                Object capsule = previous == null ? null : previous.capsule();
                Object shade = previous == null ? null : previous.shade();
                listChanged |= capsule == null || popup;
                shadeChanged |= rules.shadeCards() != (shade != null);
                boolean clearable = group.stream().allMatch(item -> item.value().isClearable());
                capsule = updateModel(capsule, render, statusEntry, popup, clearable, rules);
                if (rules.shadeCards()) shade = updateModel(shade, render, shadeEntry, false, clearable, rules);
                else if (shade != null) { retired.add(shade); shade = null; }
                // A previously prepared capsule alone does not justify hiding this
                // notification when its newly requested shade projection is still pending.
                if (rules.shadeCards() && (previous == null || previous.shade() == null))
                    for (var original : group) feature.store.failed(original.key());
                next.put(key, new Projection(capsule, shade, version, group));
            } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
                for (var original : group) feature.store.failed(original.key());
                feature.failure("Legacy notification template unavailable; original retained", failure);
            }
        }
        for (var entry : projections.entrySet()) if (!next.containsKey(entry.getKey())) {
            Projection obsolete = entry.getValue();
            retired.add(obsolete.capsule());
            if (obsolete.shade() != null) { retired.add(obsolete.shade()); shadeChanged = true; }
            listChanged = true;
        }
        listChanged |= !List.copyOf(next.keySet()).equals(List.copyOf(projections.keySet()));
        projections = next;
        previousRules = rules;
        if (listChanged) Reflect.call(changed, statusListener, next.values().stream().map(Projection::capsule).toList());
        if (shadeChanged || listChanged && rules.shadeCards())
            Reflect.call(changed, shadeListener, next.values().stream().map(Projection::shade).filter(v -> v != null).toList());
        for (Object obsolete : retired) try { Reflect.call(destroy, obsolete); }
        catch (RuntimeException | LinkageError failure) {
            feature.failure("Legacy notification template cleanup deferred to native renderer", failure);
        }
        feature.main.removeCallbacks(poll);
        if (!next.isEmpty()) {
            remainingPolls = 24;
            poll();
        }
    }

    private Object updateModel(Object existing, StatusBarNotification sourceNotification, Object entry, boolean popup,
            boolean clearable, NotificationCloudRules rules)
            throws ReflectiveOperationException {
        StatusBarNotification render = sourceNotification.clone();
        var extras = render.getNotification().extras;
        extras.putString("oplusLiveAlertOptions", metadata(popup, clearable).toString());
        if (entry == statusEntry) {
            String caption = NativeNotificationCapsule.captionText(render, host, rules);
            if (caption.length() > 512) caption = caption.substring(0, caption.offsetByCodePoints(0, 256));
            extras.putString(NativeNotificationCapsule.SHORT_CRITICAL_TEXT, caption);
        }
        Object value = existing == null ? model.newInstance(render, entry) : existing;
        if (existing != null) source.set(value, render);
        Reflect.call(prepare, value);
        return value;
    }

    static JSONObject metadata(boolean popup, boolean clearable) {
        try {
            return new JSONObject().put("isMilestone", false).put("remindType", popup ? 11 : 0)
                    .put("extensibleActionMap", new JSONObject().put("isDismissible", clearable));
        } catch (JSONException error) { throw new IllegalStateException(error); }
    }

    private boolean rendered(Object model) {
        if (model == null || !Boolean.TRUE.equals(Reflect.call(ready, model))) return false;
        for (Object wrapper : (List<?>) Reflect.call(wrappers, model))
            if (!(Reflect.call(view, wrapper) instanceof ViewGroup row) || row.getChildCount() == 0) return false;
        return true;
    }

    private void poll() {
        try {
            boolean pending = false, updated = false;
            for (Projection projection : projections.values()) {
                if (projection.members().stream().allMatch(item -> feature.store.ready(item.key()))) continue;
                if (rendered(projection.capsule()) && (projection.shade() == null || rendered(projection.shade())))
                    for (var item : projection.members()) updated |= feature.ready(item.key(), item.revision());
                else pending = true;
            }
            if (updated) feature.readinessChanged();
            if (pending && --remainingPolls > 0) feature.main.postDelayed(poll, 50L);
        } catch (RuntimeException | LinkageError failure) {
            feature.failure("Legacy notification view readiness unavailable; original retained", failure);
        }
    }

    @Override public void close() {
        try { update(List.of(), NotificationCloudRules.DEFAULT); }
        finally {
            hooks.forEach(XposedInterface.HookHandle::unhook);
            hooks.clear();
        }
    }
}
