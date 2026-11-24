package dev.lsposed.fluidcloud;

import android.content.Context;
import android.graphics.drawable.Icon;
import android.os.Bundle;
import android.service.notification.StatusBarNotification;
import android.view.View;
import dev.howard.colorosenhance.notifications.NotificationCloudRules;
import dev.howard.colorosenhance.notifications.NotificationCloudStore;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Feed ordinary notifications into the OEM notification renderer, rather than cloning its window. */
final class NativeNotificationCloud implements NotificationCloudRenderer {
    final ClassLoader loader;
    private final Context host;
    private final NotificationCloudFeature feature;
    private final Object parser, entry, listener;
    private final Object shadeEntry, shadeListener;
    private final Constructor<?> modelConstructor;
    private final Method parse, changed, prepare, updateViews, wrappers;
    private final Field source, ui, wrapperSize, wrapperView;
    private final Method rebindWrapper;
    private final Field placementMetadata;
    private final Map<String, Object> models = new LinkedHashMap<>();
    private final Map<String, Object> shadeModels = new LinkedHashMap<>();
    private final Map<String, NotificationCloudGrouping.RenderVersion> versions = new LinkedHashMap<>();
    private final Map<String, String> captions = new LinkedHashMap<>();
    private final java.util.Set<String> waitingForTouch = new java.util.HashSet<>();
    private final Map<String, List<NotificationCloudStore.Entry<StatusBarNotification>>> coverage = new LinkedHashMap<>();
    private final Map<String, String> representatives = new LinkedHashMap<>();
    private final NativeNotificationControls controls;
    private final NativeNotificationReminders reminders;
    private final Runnable readinessCheck = this::pollReadiness;
    private int readinessChecksLeft;
    private boolean readinessQueued;
    private final NativeNotificationCapsule capsule;
    private final NativeShadeNotificationDismissal shadeDismissal;
    private record Appearance(NotificationCloudRules.Style style, NotificationCloudRules.CapsuleText text, int limit,
            boolean floating, Map<String, NotificationCloudRules.Floating> appFloating) { }
    private Appearance appearance;
    private boolean shadeShown, grouped, lockedPlacement;
    private List<NotificationCloudStore.Entry<StatusBarNotification>> projected;

    NativeNotificationCloud(NotificationCloudFeature feature, View root, Context host) throws ReflectiveOperationException {
        this.feature = feature; this.host = host; loader = root.getClass().getClassLoader();
        Class<?> entryType = Reflect.type(loader, "com.oplus.systemui.plugins.livealert.lib.EntryType");
        entry = enumValue(entryType, "ENTRY_STATUS_BAR");
        Class<?> data = Reflect.type(loader, "com.oplus.systemui.plugins.shared.api.d");
        Class<?> model = Reflect.type(loader, "com.oplus.systemui.livealert.notification.origin.d");
        modelConstructor = model.getDeclaredConstructor(StatusBarNotification.class, data, entryType);
        Class<?> parserType = Reflect.type(loader, "com.oplus.systemui.plugins.shared.data.parse.r");
        parser = parserType.getDeclaredConstructor(Context.class, Context.class).newInstance(root.getContext(), host);
        parse = Reflect.signature(parserType, data, StatusBarNotification.class);
        source = Reflect.field(model, "a", StatusBarNotification.class); ui = Reflect.field(model, "b", data);
        placementMetadata = Reflect.field(model, "d", org.json.JSONObject.class);
        prepare = Reflect.method(model, "A", void.class); updateViews = Reflect.method(model, "B", void.class);
        wrappers = Reflect.method(model, "j", List.class);
        Field size = null, view = null;
        Method rebind = null;
        try {
            Class<?> wrapper = Reflect.type(loader, "com.oplus.systemui.livealert.notification.origin.e");
            size = Reflect.field(wrapper, "d", Reflect.type(loader, "com.oplus.systemui.plugins.livealert.lib.ViewSize"));
            view = Reflect.field(wrapper, "c", Object.class);
            rebind = Reflect.method(view.getType(), "o", void.class, data);
        } catch (ReflectiveOperationException unavailable) { /* The stock whole-model refresh remains available. */ }
        wrapperSize = size;
        wrapperView = view;
        rebindWrapper = rebind;
        var binding = NotificationPluginRepository.resolve(loader, entryType);
        Object repository = Reflect.get(binding.singleton(), null);
        listener = binding.listener().newInstance(repository, entry);
        changed = binding.publish();
        Object shade = null, shadeUpdates = null;
        try {
            shade = enumValue(entryType, "ENTRY_NOTIFICATION");
            shadeUpdates = binding.listener().newInstance(repository, shade);
            installShadeLaunchObserver(feature, loader);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError unavailable) {
            feature.failure("Notification-center native card entry unavailable", unavailable);
        }
        shadeEntry = shade;
        shadeListener = shadeUpdates;
        NativeShadeNotificationDismissal shadeSwipe = null;
        if (shadeListener != null) try {
            shadeSwipe = new NativeShadeNotificationDismissal(feature, loader, host.getClassLoader(), binding.wrapped());
            feature.module.info("Native notification-center swipe dismissal installed");
        } catch (ReflectiveOperationException | RuntimeException | LinkageError unavailable) {
            feature.failure("Native notification-center swipe dismissal unavailable", unavailable);
        }
        shadeDismissal = shadeSwipe;
        NativeNotificationCapsule capsuleOptions = null;
        try { capsuleOptions = new NativeNotificationCapsule(feature, loader, root.getContext()); }
        catch (ReflectiveOperationException | RuntimeException | LinkageError unavailable) {
            feature.failure("Converted capsule caption and width unavailable", unavailable);
        }
        capsule = capsuleOptions;
        try { NativeNotificationBuilds.install(feature, loader, binding, entryType); }
        catch (ReflectiveOperationException | RuntimeException | LinkageError unavailable) {
            feature.failure("Extended notification build queue unavailable; unprepared originals retained", unavailable);
        }
        NativeNotificationControls optional;
        try { optional = new NativeNotificationControls(feature, root, binding); }
        catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
            optional = null; feature.failure("Notification capsule controls unavailable; original notifications retained", failure);
        }
        controls = optional;
        NativeNotificationReminders nativeReminders = null;
        if (controls != null) try {
            nativeReminders = new NativeNotificationReminders(model, placementMetadata);
            nativeReminders.installDuration(feature, loader, binding.wrapped());
        } catch (ReflectiveOperationException | RuntimeException | LinkageError unavailable) {
            feature.failure(nativeReminders == null ? "Native notification auto-expansion unavailable"
                    : "Native notification auto-expansion uses the system duration", unavailable);
        }
        reminders = nativeReminders;
    }
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Object enumValue(Class<?> type, String name) { return Enum.valueOf((Class) type, name); }

    @Override public ClassLoader loader() { return loader; }

    @Override public void update(List<NotificationCloudStore.Entry<StatusBarNotification>> notifications, NotificationCloudRules rules,
            Set<String> autoExpand) {
        Appearance rendering = new Appearance(rules.style(), rules.capsuleText(), feature.capsuleMaxDp(),
                rules.floating(), rules.appFloating());
        boolean changedAppearance = !rendering.equals(appearance);
        boolean lockCards = !feature.unlocked() && rules.retainOnLock() && rules.lockCards();
        boolean placementChanged = lockCards != lockedPlacement;
        boolean showShade = (feature.unlocked() ? rules.shadeCards() : lockCards) && shadeListener != null && !notifications.isEmpty();
        if (!placementChanged && autoExpand.isEmpty() && !changedAppearance && showShade == shadeShown && grouped == rules.groupApps()
                && notifications.equals(projected)) return;
        projected = null;
        boolean complete = true;
        boolean reminderChanged = false;
        List<Object> next = new ArrayList<>();
        List<Object> shadeNext = new ArrayList<>();
        Map<String, NotificationCloudGrouping.RenderVersion> nextVersions = new LinkedHashMap<>();
        List<Object> removed = new ArrayList<>(models.values());
        List<Object> removedFromShade = new ArrayList<>(shadeModels.values());
        List<String> previousShade = List.copyOf(shadeModels.keySet());
        coverage.clear(); representatives.clear();
        for (var group : NotificationCloudGrouping.groups(notifications, rules.groupApps())) {
            var item = group.get(0);
            var version = NotificationCloudGrouping.renderVersion(group);
            try {
                Object model = models.get(item.key());
                Object panelModel = shadeModels.get(item.key());
                boolean rerender = placementChanged || changedAppearance || waitingForTouch.contains(item.key()) || !version.equals(versions.get(item.key()));
                if (!lockCards && !placementChanged && rerender && model != null && controls != null && controls.holds(item.key())) {
                    waitingForTouch.add(item.key());
                    rerender = false;
                    version = versions.get(item.key());
                } else waitingForTouch.remove(item.key());
                if (model == null || rerender || showShade && panelModel == null) {
                    StatusBarNotification render = feature.renderNotification(group);
                    String label = capsule == null ? "" : capsule.caption(render, host, rules, rendering.limit());
                    Object template = capsule == null ? Reflect.call(parse, parser, render)
                            : capsule.parse(parse, parser, render, label);
                    if (template == null) {
                        complete = false;
                        for (var member : group) feature.store.failed(member.key());
                        continue;
                    }
                    if (model == null || rerender) {
                        boolean keepCapsule = model != null && rendering.limit() > 0 && !label.isEmpty()
                                && !placementChanged && !changedAppearance && label.equals(captions.get(item.key()))
                                && sameResourceIcon((StatusBarNotification) Reflect.get(source, model), render);
                        model = updateModel(model, render, template, entry, keepCapsule);
                        models.put(item.key(), model);
                        captions.put(item.key(), label);
                    }
                    if (showShade) try {
                        // A separate projection uses the vendor's notification-center entry.
                        panelModel = updateModel(panelModel, render, template, shadeEntry, false);
                        shadeModels.put(item.key(), panelModel);
                    } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
                        complete = false;
                        panelModel = null;
                        feature.failure("Native shade card unavailable", failure);
                    }
                }
                if (reminders != null && !waitingForTouch.contains(item.key())) {
                    var latest = group.getLast();
                    boolean request = autoExpand.contains(latest.key());
                    Object seconds = request ? feature.module.cloudValues().get("notification_popup_seconds") : null;
                    try {
                        reminderChanged |= reminders.update(model, latest.key(), latest.value().getPostTime(), request, seconds);
                    } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
                        feature.failure("Native notification reminder unavailable", failure);
                    }
                }
                updatePlacement(model, false, lockCards);
                if (showShade && panelModel != null) updatePlacement(panelModel, true, lockCards);
                if (lockCards && (placementChanged || panelModel == null))
                    for (var member : group) feature.store.failed(member.key());
                next.add(model); removed.remove(model); nextVersions.put(item.key(), version);
                if (showShade && panelModel != null) { shadeNext.add(panelModel); removedFromShade.remove(panelModel); }
                coverage.put(item.key(), group); for (var member : group) representatives.put(member.key(), item.key());
            } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
                complete = false;
                for (var member : group) feature.store.failed(member.key());
                feature.failure("Notification template unavailable; original retained", failure);
            }
        }
        boolean listChanged = !List.copyOf(nextVersions.keySet()).equals(List.copyOf(versions.keySet()));
        appearance = rendering;
        models.keySet().retainAll(nextVersions.keySet());
        captions.keySet().retainAll(nextVersions.keySet());
        waitingForTouch.retainAll(nextVersions.keySet());
        shadeModels.values().removeAll(removedFromShade);
        versions.clear(); versions.putAll(nextVersions);
        if (placementChanged || listChanged || reminderChanged) Reflect.call(changed, listener, next);
        if (shadeDismissal != null) shadeDismissal.publish(shadeModels, coverage);
        if (shadeListener != null && (!previousShade.equals(List.copyOf(shadeModels.keySet())) || shadeShown != showShade || placementChanged))
            Reflect.call(changed, shadeListener, shadeNext);
        lockedPlacement = lockCards;
        shadeShown = showShade;
        grouped = rules.groupApps();
        removed.addAll(removedFromShade);
        for (Object obsolete : removed) {
            if (reminders != null) reminders.forget(obsolete);
            try { Reflect.call(Reflect.method(obsolete.getClass(), "x", void.class), obsolete); }
            catch (ReflectiveOperationException | RuntimeException unavailable) { feature.module.info("Notification template cleanup deferred to native renderer"); }
        }
        if (listChanged || placementChanged) {
            cancelReadiness();
            readinessChecksLeft = 24;
        }
        if (!readinessQueued) pollReadiness();
        // Keep retrying failed projections and revisions deferred during card touches.
        if (complete && waitingForTouch.isEmpty() && notifications.stream().allMatch(item -> feature.store.ready(item.key())))
            projected = notifications;
    }
    private Object updateModel(Object model, StatusBarNotification sbn, Object template, Object placement, boolean keepCapsule)
            throws ReflectiveOperationException {
        if (model == null) {
            model = modelConstructor.newInstance(sbn, template, placement);
            Reflect.call(prepare, model);
        } else {
            source.set(model, sbn); ui.set(model, template);
            // The source's identity and placement are unchanged. Preparing again resets
            // the native priority/timing even for text-only updates.
            if (keepCapsule && rebindWrapper != null) {
                for (Object wrapper : (List<?>) Reflect.call(wrappers, model)) {
                    if (!"VIEW_SIZE_SM".equals(Reflect.get(wrapperSize, wrapper).toString()))
                        Reflect.call(rebindWrapper, Reflect.get(wrapperView, wrapper), template);
                }
            } else Reflect.call(updateViews, model);
        }
        return model;
    }
    private static boolean sameResourceIcon(StatusBarNotification old, StatusBarNotification next) {
        Icon before = old.getNotification().getSmallIcon(), after = next.getNotification().getSmallIcon();
        return before != null && after != null && before.getType() == Icon.TYPE_RESOURCE
                && after.getType() == Icon.TYPE_RESOURCE && before.getResId() == after.getResId()
                && java.util.Objects.equals(before.getResPackage(), after.getResPackage());
    }
    private void updatePlacement(Object model, boolean shade, boolean lockCards) throws IllegalAccessException {
        var current = (org.json.JSONObject) placementMetadata.get(model);
        var updated = NotificationLockscreenCards.placement(current, shade, lockCards);
        if (updated != current) placementMetadata.set(model, updated);
    }
    private static void installShadeLaunchObserver(NotificationCloudFeature feature, ClassLoader loader)
            throws ReflectiveOperationException {
        Class<?> callback = Reflect.type(loader, "com.oplus.systemui.plugins.seedling.notification.l");
        Class<?> model = Reflect.type(loader, "com.oplus.systemui.plugins.livealert.lib.m");
        Method launch = Reflect.method(callback, "f", boolean.class, model, View.class, List.class, boolean.class,
                android.app.PendingIntent.class, Bundle.class);
        Method key = Reflect.method(model, "getKey", String.class);
        feature.module.hook(launch).setExceptionMode(io.github.libxposed.api.XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
            if (feature.enabled() && feature.rules().shadeCards() && chain.getArg(4) instanceof android.app.PendingIntent target)
                feature.armGroupOpen((String) Reflect.call(key, chain.getArg(0)), target);
            return chain.proceed();
        });
    }
    @Override public boolean available() { return controls != null; }
    @Override public int capsuleWidthLimit(View view) { return capsule == null ? Integer.MAX_VALUE : capsule.widthLimit(view); }
    @Override public void closeCard(Runnable continuation) { if (controls != null) controls.closeCard(continuation); }
    @Override public boolean open(String key) {
        Object model = models.get(representatives.getOrDefault(key, key)); if (model == null) return openWithActivityStarter(feature, host, key);
        try {
            List<?> views = (List<?>) Reflect.call(wrappers, model);
            for (String preferred : List.of("VIEW_SIZE_LG", "VIEW_SIZE_SM")) for (Object view : views) {
                Object size = Reflect.call(Reflect.method(view.getClass(), "d", Reflect.type(loader, "com.oplus.systemui.plugins.livealert.lib.ViewSize")), view);
                if (!size.toString().equals(preferred)) continue;
                Object callback = Reflect.get(Reflect.field(view.getClass(), "e", Reflect.type(loader, "com.oplus.systemui.plugins.livealert.lib.i")), view);
                if (callback == null) continue;
                Object nativeView = Reflect.call(Reflect.method(view.getClass(), "f", View.class, boolean.class), view, true);
                var original = feature.store.get(key); if (original == null || original.value().getNotification().contentIntent == null) return false;
                Class<?> base = Reflect.type(loader, "com.oplus.systemui.plugins.livealert.lib.m");
                Method launch = Reflect.signature(callback.getClass(), boolean.class, base, View.class, List.class, boolean.class, android.app.PendingIntent.class, Bundle.class);
                // OEM callback's Bundle is a distinct launch command, not ActivityOptions.
                // A non-null options bundle masks the PendingIntent and opens nothing.
                if (Boolean.TRUE.equals(Reflect.call(launch, callback, model, nativeView, new ArrayList<>(), true, original.value().getNotification().contentIntent, null))) return true;
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
            feature.failure("Notification destination unavailable; original retained", failure);
        }
        return openWithActivityStarter(feature, host, key);
    }
    static boolean openWithActivityStarter(NotificationCloudFeature feature, Context host, String key) {
        var item = feature.store.get(key);
        if (item == null || !feature.unlocked()) return false;
        android.app.PendingIntent target = item.value().getNotification().contentIntent;
        if (target == null) return false;
        try {
            // Menus outlive the card view's click callback. Use the same authenticated
            // SystemUI launcher as QS instead of sending an activity behind the panel.
            Class<?> dependency = Class.forName("com.android.systemui.Dependency", false, host.getClassLoader());
            Object owner = Reflect.get(Reflect.field(dependency, "sDependency", dependency), null);
            Class<?> starterType = Class.forName("com.android.systemui.plugins.ActivityStarter", false, host.getClassLoader());
            Object starter = Reflect.call(Reflect.method(dependency, "getDependencyInner", Object.class, Object.class), owner, starterType);
            Reflect.call(Reflect.method(starter.getClass(), "startPendingIntentDismissingKeyguard", void.class, android.app.PendingIntent.class), starter, target);
            return true;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError unavailable) {
            feature.failure("SystemUI notification launcher unavailable; original retained", unavailable);
            return false;
        }
    }
    private void pollReadiness() {
        readinessQueued = false;
        if (controls == null) return;
        boolean waiting = false, changed = false;
        for (var item : models.entrySet()) {
            var members = coverage.get(item.getKey());
            if (members == null || members.stream().allMatch(member -> feature.store.ready(member.key()))) continue;
            List<?> ready = (List<?>) Reflect.call(wrappers, item.getValue());
            Object panel = shadeModels.get(item.getKey());
            boolean lockRowReady = !lockedPlacement || panel != null && !((List<?>) Reflect.call(wrappers, panel)).isEmpty();
            if (ready.size() >= 2 && lockRowReady) for (var member : members) changed |= feature.ready(member.key(), member.revision());
            else waiting = true;
        }
        if (changed) feature.readinessChanged();
        if (waiting && readinessChecksLeft > 0) {
            readinessChecksLeft--;
            readinessQueued = feature.main.postDelayed(readinessCheck, 50);
        }
    }
    private void cancelReadiness() {
        if (readinessQueued) feature.main.removeCallbacks(readinessCheck);
        readinessQueued = false;
        readinessChecksLeft = 0;
    }
    @Override public void close() {
        cancelReadiness();
        projected = null;
        if (shadeDismissal != null) shadeDismissal.close();
        Reflect.call(changed, listener, List.of());
        if (shadeListener != null) Reflect.call(changed, shadeListener, List.of());
        models.clear(); shadeModels.clear(); versions.clear(); captions.clear(); waitingForTouch.clear();
        coverage.clear(); representatives.clear();
        if (controls != null) controls.close();
        if (reminders != null) reminders.close();
        if (capsule != null) capsule.close();
    }
}
