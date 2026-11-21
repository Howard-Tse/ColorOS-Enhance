package dev.lsposed.fluidcloud;

import android.app.KeyguardManager;
import dev.howard.colorosenhance.diagnostics.DiagnosticPoint;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Handler;
import android.os.Looper;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.view.View;
import dev.howard.colorosenhance.notifications.NotificationCloudRules;
import dev.howard.colorosenhance.notifications.NotificationCloudStore;
import io.github.libxposed.api.XposedInterface;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** Uses SystemUI's existing notification collection; never binds a second listener service. */
final class NotificationCloudFeature {
    final HookEntry module;
    final Handler main = new Handler(Looper.getMainLooper());
    final NotificationCloudStore<StatusBarNotification> store = new NotificationCloudStore<>();
    private final Map<String, Object> entries = new LinkedHashMap<>();
    private final Method all, sbn, ranking, findEntry;
    private Object collection;
    private Context context;
    private NotificationCloudRenderer nativeCloud;
    private NotificationCloudPresentation presentation;
    private boolean scheduled, active, updatingNative;
    private volatile boolean configured;
    private boolean screenBlocked;
    private boolean unlockedSnapshot;
    private Object observedCollection, collectionObserver;
    private final Set<String> newNotificationKeys = new HashSet<>();
    private NotificationCloudActions actions;
    private NotificationCollectionPage collectionPage;
    private final NotificationOpenCleanup openCleanup;
    private int capsuleMaxDp;
    private final android.os.Binder alertRenderer = new android.os.Binder();
    private final java.util.concurrent.ExecutorService alertStatusWorker = java.util.concurrent.Executors.newSingleThreadExecutor(
            task -> new Thread(task, "coloros-notification-alert-status"));
    private Boolean lastAlertStatus;
    private volatile int alertStatusGeneration;
    private NotificationCloudRules rules = NotificationCloudRules.DEFAULT;
    private final Runnable refreshTask = () -> { scheduled = false; refresh(); };

    NotificationCloudFeature(HookEntry module, ClassLoader loader) throws ReflectiveOperationException {
        this.module = module;
        Class<?> type = Reflect.type(loader, "com.android.systemui.statusbar.notification.collection.NotifCollection");
        Class<?> entry = Reflect.type(loader, "com.android.systemui.statusbar.notification.collection.NotificationEntry");
        all = Reflect.method(type, "getAllNotifs", Collection.class);
        sbn = Reflect.method(entry, "getSbn", StatusBarNotification.class);
        ranking = Reflect.method(entry, "getRanking", NotificationListenerService.Ranking.class);
        findEntry = Reflect.method(type, "getEntry", entry, String.class);
        openCleanup = new NotificationOpenCleanup(this, loader, type, entry);
        Class<?> pipeline = Reflect.type(loader, "com.android.systemui.statusbar.notification.collection.NotifPipeline");
        Class<?> observerType = Reflect.type(loader, "com.android.systemui.statusbar.notification.collection.notifcollection.NotifCollectionListener");
        collectionObserver = Reflect.listener(observerType, (event, args) -> {
            if (!configured && !active) return;
            if (event.getName().equals("onEntryAdded") && enabled() && active && unlocked())
                newNotificationKeys.add(notification(args[0]).getKey());
            if (event.getName().equals("onEntryRemoved")) {
                String key = notification(args[0]).getKey();
                newNotificationKeys.remove(key);
                store.remove(key);
            }
            changed();
        });
        for (var constructor : pipeline.getDeclaredConstructors()) {
            Class<?>[] parameters = constructor.getParameterTypes();
            for (int i = 0; i < parameters.length; i++) if (parameters[i] == type) {
                int argument = i;
                module.hook(constructor).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
                    Object value = chain.getArg(argument); Object result = chain.proceed(); captureCollection(value); return result;
                });
            }
        }
        module.hook(all).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
            if (collection == null) captureCollection(chain.getThisObject());
            return chain.proceed();
        });
        try {
            Class<?> keyguard = Reflect.type(loader, "com.android.systemui.statusbar.policy.KeyguardStateControllerImpl");
            Method change = Reflect.method(keyguard, "notifyKeyguardState", void.class, boolean.class, boolean.class);
            module.hook(change).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
                Object result = chain.proceed(); screenBlocked = (boolean) chain.getArg(0);
                if (screenBlocked) unlockedSnapshot = false;
                changed(); return result;
            });
        } catch (ReflectiveOperationException unavailable) { module.info("Notification keyguard observer uses protected broadcasts"); }
        try { presentation = new NotificationCloudPresentation(this, loader, entry); }
        catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
            failure("Notification collection presentation unavailable; original notifications retained", failure);
        }
        for (var constructor : type.getDeclaredConstructors()) module.hook(constructor)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
                    Object result = chain.proceed(); captureCollection(chain.getThisObject()); return result;
                });
        for (Method method : type.getDeclaredMethods()) if (method.getName().equals("postNotification")
                || method.getName().equals("tryRemoveNotification") || method.getName().equals("dispatchEventsAndRebuildList")) {
            module.hook(method).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
                Object result = chain.proceed(); collection = chain.getThisObject(); changed(); return result;
            });
        }
        configurationChanged(module.cloudValues());
    }
    private void captureCollection(Object value) {
        if (value == null) return;
        collection = value;
        main.post(() -> {
            if (observedCollection == value) return;
            try {
                Object listeners = Reflect.get(Reflect.field(value.getClass(), "mNotifCollectionListeners", Object.class), value);
                Method add;
                try { add = Reflect.method(listeners.getClass(), "addIfAbsent", boolean.class, Object.class); }
                catch (NoSuchMethodException legacy) { add = Reflect.method(listeners.getClass(), "add", boolean.class, Object.class); }
                Reflect.call(add, listeners, collectionObserver); observedCollection = value;
                module.info("Notification collection lifecycle observer connected"); changed();
            } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
                failure("Notification lifecycle observer unavailable; originals retained", failure);
            }
        });
    }
    void context(View root, Context host) {
        if (context == null) {
            context = host;
            actions = new NotificationCloudActions(this, host);
            try { NativeNotificationMenu.install(this, actions, host.getClassLoader()); }
            catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
                failure("ColorOS notification-card menu unavailable", failure);
            }
            try {
                collectionPage = new NotificationCollectionPage(this, host.getClassLoader());
            } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
                failure("In-shade collection page unavailable; originals retained", failure);
            }
            NotificationChannelMetadata.install(this, host);
            if (presentation != null) presentation.context(host);
            alertStatusWorker.execute(() -> {
                try { host.getContentResolver().call(dev.howard.colorosenhance.notifications.NotificationMetadataProvider.URI,
                        "clear_legacy_collection", null, null); }
                catch (RuntimeException unavailable) { /* A leftover row is also excluded from the shade and status bar. */ }
            });
            BroadcastReceiver receiver = new BroadcastReceiver() {
                @Override public void onReceive(Context ignored, Intent intent) {
                    if (Intent.ACTION_SCREEN_OFF.equals(intent.getAction())) { screenBlocked = true; unlockedSnapshot = false; }
                    if (Intent.ACTION_USER_PRESENT.equals(intent.getAction())) screenBlocked = false;
                    changed();
                }
            };
            IntentFilter filter = new IntentFilter();
            filter.addAction(Intent.ACTION_SCREEN_OFF); filter.addAction(Intent.ACTION_USER_PRESENT);
            filter.addAction("android.intent.action.USER_SWITCHED"); filter.addAction(Intent.ACTION_MANAGED_PROFILE_UNAVAILABLE);
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED);
        }
        ClassLoader loader = root.getClass().getClassLoader();
        if (nativeCloud == null || nativeCloud.loader() != loader) try {
            if (nativeCloud != null) nativeCloud.close();
            Class<?> container = Reflect.type(loader, NativeCapsuleAppearance.VIEWS + "CapsuleContainer");
            nativeCloud = ColorOs16Plugin.capsule(container) ? new LegacyNotificationCloud(this, loader, host)
                    : new NativeNotificationCloud(this, root, host);
            changed();
            module.report(DiagnosticPoint.NOTIFICATION_RENDERER, DiagnosticPoint.State.READY);
            module.info("Notification cloud native source connected");
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
            nativeCloud = null;
            module.report(DiagnosticPoint.NOTIFICATION_RENDERER, DiagnosticPoint.State.UNSUPPORTED);
            failure("Notification cloud contract unavailable; original notifications retained", failure);
        }
    }
    void changed() {
        if (!configured && !active) return;
        if (!scheduled) { scheduled = true; main.postDelayed(refreshTask, 60); }
    }
    void configurationChanged(Map<String, ?> values) {
        NotificationCloudRules next = NotificationCloudRules.from(values);
        boolean enabled = Boolean.TRUE.equals(values.get("enabled")) && Boolean.TRUE.equals(values.get(NotificationCloudRules.ENABLED));
        int max = values.get("notification_capsule_max_dp") instanceof Number n ? Math.max(0, Math.min(240, n.intValue())) : 0;
        main.post(() -> {
            if (configured == enabled && capsuleMaxDp == max && rules.equals(next)) return;
            rules = next; configured = enabled; capsuleMaxDp = max;
            changed();
        });
    }
    boolean enabled() {
        return configured;
    }
    boolean unlocked() {
        return unlockedSnapshot && !screenBlocked;
    }
    NotificationCloudRules rules() { return rules; }
    int capsuleMaxDp() { return capsuleMaxDp; }
    int capsuleWidthLimit(View view) { return nativeCloud == null ? Integer.MAX_VALUE : nativeCloud.capsuleWidthLimit(view); }
    private void refresh() {
        KeyguardManager keyguard = context == null ? null : context.getSystemService(KeyguardManager.class);
        unlockedSnapshot = !screenBlocked && keyguard != null && !keyguard.isKeyguardLocked();
        boolean nextActive = enabled() && unlocked() && nativeCloud != null;
        publishAlertStatus(nextActive && collection != null && nativeCloud.available() && !rules.keepAlerts());
        if (!nextActive || collection == null) {
            if (presentation != null) presentation.clear();
            if (collectionPage != null) collectionPage.close();
            if (active && nativeCloud != null) nativeCloud.update(java.util.List.of(), rules);
            // A successful open stays cleared until the source notification is removed, including across lock/unlock.
            if (!enabled()) store.clear(); else store.forgetViews();
            if (actions != null) actions.retainFloatingButtons(java.util.Set.of());
            entries.clear(); newNotificationKeys.clear(); active = false; return;
        }
        try {
            var next = new LinkedHashMap<String, Object>();
            Set<String> reminders = newNotificationKeys.isEmpty() ? Set.of() : new HashSet<>();
            java.util.Set<String> liveKeys = new java.util.HashSet<>();
            var live = new ArrayList<StatusBarNotification>();
            for (Object entry : (Collection<?>) Reflect.call(all, collection)) {
                StatusBarNotification notification = notification(entry);
                live.add(notification);
                if (NotificationCloudPresentation.summary(notification)) continue;
                String key = notification.getKey();
                liveKeys.add(key);
                var mode = mode(entry, notification);
                if (mode == NotificationCloudRules.Mode.NATIVE) continue;
                next.put(key, entry);
                var current = store.get(key);
                if (mode == NotificationCloudRules.Mode.EXPAND && newNotificationKeys.contains(key)) reminders.add(key);
                if (current == null || current.value() != notification) store.put(key, notification.getPackageName(),
                        notification.getNotification().getChannelId(), notification);
            }
            store.retainSuppressed(liveKeys);
            newNotificationKeys.clear();
            Set<String> autoExpand = !reminders.isEmpty() && permitsAutoExpansion() ? reminders : Set.of();
            if (actions != null) actions.retainFloatingButtons(next.keySet());
            for (var entry : store.entries()) if (!next.containsKey(entry.key())) store.forgetView(entry.key());
            entries.clear(); entries.putAll(next);
            updatingNative = true;
            try { nativeCloud.update(store.entries().stream().filter(e -> !store.suppressed(e.key())).toList(), rules, autoExpand); }
            finally { updatingNative = false; }
            if (presentation != null) presentation.refresh(live);
            if (collectionPage != null) collectionPage.refresh();
            active = true;
        } catch (RuntimeException | LinkageError failure) {
            newNotificationKeys.clear();
            store.clear(); if (nativeCloud != null) nativeCloud.update(java.util.List.of(), rules);
            readinessChanged();
            failure("Notification conversion failed; originals retained", failure);
        }
    }
    private boolean permitsAutoExpansion() {
        try {
            NotificationManager manager = context.getSystemService(NotificationManager.class);
            return manager != null && manager.getCurrentInterruptionFilter() == NotificationManager.INTERRUPTION_FILTER_ALL;
        } catch (SecurityException unavailable) {
            return false;
        }
    }
    private void publishAlertStatus(boolean available) {
        if (context == null) return;
        if (Boolean.valueOf(available).equals(lastAlertStatus)) return;
        lastAlertStatus = available;
        int generation = ++alertStatusGeneration;
        android.os.Bundle args = new android.os.Bundle();
        args.putBinder("renderer", available ? alertRenderer : null);
        alertStatusWorker.execute(() -> {
            if (generation != alertStatusGeneration) return;
            try { context.getContentResolver().call(dev.howard.colorosenhance.notifications.NotificationMetadataProvider.URI,
                    "publish_alert_renderer", null, args); }
            catch (RuntimeException unavailable) {
                main.post(() -> {
                    if (generation == alertStatusGeneration) lastAlertStatus = null;
                });
            }
        });
    }
    void failure(String message, Throwable error) {
        // Parser exception messages can include application-supplied notification text.
        Throwable cause = error;
        for (int i = 0; i < 8 && cause.getCause() != null && cause.getCause() != cause; i++) cause = cause.getCause();
        module.info(message + " (" + cause.getClass().getSimpleName() + ")");
    }
    private boolean eligible(Object entry, StatusBarNotification sbn) {
        return mode(entry, sbn) != NotificationCloudRules.Mode.NATIVE;
    }
    boolean mayReplaceBanner(Object entry, StatusBarNotification notification) {
        if (!enabled() || !unlocked() || nativeCloud == null || !nativeCloud.available() || notification == null
                || !rules.replaceHeadsUp()) return false;
        return !store.suppressed(notification.getKey()) && eligible(entry, notification);
    }
    boolean ready(String key, long generation) {
        var item = store.get(key);
        return item != null && store.ready(key, generation) && !item.ready();
    }
    void readinessChanged() {
        // Synchronous readiness is picked up by refresh() with its collection snapshot.
        if (updatingNative) return;
        if (presentation != null) presentation.refresh();
        if (collectionPage != null) collectionPage.refresh();
    }
    private NotificationCloudRules.Mode mode(Object entry, StatusBarNotification sbn) {
        if (!rules.accepts(sbn.getPackageName())) return NotificationCloudRules.Mode.NATIVE;
        if (Notification.CATEGORY_CALL.equals(sbn.getNotification().category)) try {
            var app = context.getPackageManager().getApplicationInfo(sbn.getPackageName(), 0);
            if ((app.flags & android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0) return NotificationCloudRules.Mode.NATIVE;
        } catch (android.content.pm.PackageManager.NameNotFoundException missing) { return NotificationCloudRules.Mode.NATIVE; }
        var rank = (NotificationListenerService.Ranking) Reflect.call(ranking, entry);
        return dev.howard.colorosenhance.notifications.NotificationCloudPolicy.evaluate(sbn, android.os.Process.myUserHandle(),
                rank.getImportance(), rank.getChannel() == null ? NotificationManager.IMPORTANCE_UNSPECIFIED : rank.getChannel().getImportance(), rank.isSuspended(), rules);
    }
    StatusBarNotification renderNotification(java.util.List<NotificationCloudStore.Entry<StatusBarNotification>> group) {
        return actions == null ? group.getFirst().value().clone() : actions.decorate(group, rules);
    }
    java.util.List<NotificationCloudStore.Entry<StatusBarNotification>> group(String key) {
        var item = store.get(key); if (item == null) return java.util.List.of();
        if (!rules.groupApps()) return java.util.List.of(item);
        String identity = NotificationCloudGrouping.identity(item.value());
        return store.entries().stream().filter(entry -> !store.suppressed(entry.key()) && NotificationCloudGrouping.identity(entry.value()).equals(identity)).toList();
    }
    void dismissCard(String key) {
        if (!enabled() || !unlocked() || !store.ready(key)) return;
        for (var entry : group(key)) {
            if (!dismissOriginal(entry.key())) returnOriginal(entry.key());
        }
    }
    boolean open(String key) {
        if (nativeCloud == null || !unlocked()) return false;
        var item = store.get(key);
        if (item == null) return false;
        armOpen(key, item.value().getNotification().contentIntent);
        return nativeCloud.open(key);
    }
    void armOpen(String key, PendingIntent target) { openCleanup.arm(key, target); }
    void armGroupOpen(String key) {
        var group = group(key);
        if (group.isEmpty()) return;
        var item = group.get(group.size() - 1);
        armOpen(item.key(), item.value().getNotification().contentIntent);
    }
    void armGroupOpen(String key, PendingIntent target) {
        var group = group(key);
        if (group.isEmpty()) return;
        var item = group.get(group.size() - 1);
        if (target.equals(item.value().getNotification().contentIntent)) armOpen(item.key(), target);
    }
    Object liveEntry(String key) { return collection == null ? null : Reflect.call(findEntry, collection, key); }
    java.util.List<StatusBarNotification> liveNotifications() {
        if (collection == null) return java.util.List.of();
        return ((Collection<?>) Reflect.call(all, collection)).stream().map(this::notification).toList();
    }
    boolean dismissOriginal(String key) { return openCleanup.dismiss(collection, liveEntry(key)); }
    void closeCard(Runnable continuation) { if (nativeCloud != null) nativeCloud.closeCard(continuation); }
    void original(String key) {
        if (collectionPage != null) collectionPage.close();
        returnOriginal(key);
        if (actions != null) actions.showOriginal(key);
    }
    Object originalEntry(String key) { return entries.get(key); }
    StatusBarNotification notification(Object entry) { return (StatusBarNotification) Reflect.call(sbn, entry); }
    void openCollection(View anchor, android.view.ViewGroup stack) {
        if (collectionPage != null) collectionPage.open(anchor, stack);
    }
    boolean collectionSupported() { return collectionPage != null; }
    boolean collectionPageShowing() { return collectionPage != null && collectionPage.projected(); }
    boolean collectionContains(StatusBarNotification notification) {
        return collectionPage != null && collectionPage.contains(notification);
    }
    void collectionChanged() { if (presentation != null) presentation.invalidate(); }
    void collectionRendered() { if (collectionPage != null) collectionPage.rendered(); }
    java.util.List<String> collectedKeys() {
        if (presentation == null) return java.util.List.of();
        return store.entries().stream().map(NotificationCloudStore.Entry::key).filter(presentation::collected).toList();
    }
    void returnOriginal(String key) { store.returnOriginal(key); if (presentation != null) presentation.refresh(); changed(); }
}
