package dev.lsposed.fluidcloud;

import android.app.NotificationManager;
import android.content.Context;
import android.service.notification.StatusBarNotification;
import dev.howard.colorosenhance.notifications.NotificationCloudRules;
import dev.howard.colorosenhance.notifications.NotificationCloudStore;
import static dev.howard.colorosenhance.notifications.NotificationMetadataProvider.LEGACY_COLLECTION_TAG;
import static dev.howard.colorosenhance.notifications.NotificationMetadataProvider.LEGACY_COLLECTION_ID;
import io.github.libxposed.api.XposedInterface;
import java.lang.reflect.Method;
import java.util.List;

/** Filters only the shade presentation after native card/collection readiness; never rewires system groups. */
final class NotificationCloudPresentation {
    private final NotificationCloudFeature feature;
    private final NotificationIconDeferral icons;
    private Object builder;
    private final Method rebuild;
    private boolean rebuildQueued;
    private final Runnable rebuildShade = this::rebuildShade;
    private NativeNotificationCollectionEntry collectionEntry;
    private boolean grouping;
    private java.util.Set<String> lastKeys = java.util.Set.of();
    private NotificationCloudRules.Original lastOriginal;
    private Object headsUp;
    private Method removeHeadsUp;
    private final java.util.Map<String, Object> deferredBinds = new java.util.HashMap<>();
    private boolean replaying;

    NotificationCloudPresentation(NotificationCloudFeature feature, ClassLoader loader, Class<?> entry) throws ReflectiveOperationException {
        this.feature = feature;
        Class<?> list = Reflect.type(loader, "com.android.systemui.statusbar.notification.collection.ShadeListBuilder");
        rebuild = Reflect.method(list, "rebuildListIfBefore", void.class, int.class);
        NotificationIconDeferral iconDeferral;
        try { iconDeferral = new NotificationIconDeferral(feature, loader, this::replaced); }
        catch (ReflectiveOperationException | RuntimeException | LinkageError unavailable) {
            feature.failure("Status-bar notification icon deferral unavailable", unavailable);
            iconDeferral = null;
        }
        icons = iconDeferral;
        for (var constructor : list.getDeclaredConstructors()) feature.module.hook(constructor)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
                    Object result = chain.proceed(); builder = chain.getThisObject(); return result;
                });
        Method filters = Reflect.method(list, "applyFilters", boolean.class, entry, long.class, List.class);
        feature.module.hook(filters).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
            Object result = chain.proceed();
            if (Boolean.TRUE.equals(result)) return result;
            Object entryRow = chain.getArg(0);
            StatusBarNotification notification = feature.notification(entryRow);
            if (icons != null && notification != null) icons.beforeShadeRender(notification, entryRow);
            if (notification != null && summary(notification)) return true;
            if (feature.collectionPageShowing())
                return notification == null || !feature.collectionContains(notification);
            if (notification != null && (collected(notification.getKey())
                    || ready(notification.getKey()) && feature.rules().original() == NotificationCloudRules.Original.HIDE)) return true;
            return result;
        });
        try { installCollectionRendering(loader); }
        catch (ReflectiveOperationException unavailable) { feature.module.info("Notification collection render callback unavailable"); }
        try { installHeadsUp(loader, entry); }
        catch (ReflectiveOperationException | RuntimeException | LinkageError unavailable) { feature.module.info("Notification banner replacement contract unavailable"); }
        try { deferInitialBanner(loader, entry); }
        catch (ReflectiveOperationException | RuntimeException | LinkageError unavailable) { feature.module.info("Notification banner deferral unavailable"); }
    }
    private void installCollectionRendering(ClassLoader loader) throws ReflectiveOperationException {
        String base = "com.android.systemui.statusbar.notification.collection.render.";
        Class<?> manager = Reflect.type(loader, base + "RenderStageManager");
        Class<?> renderer = Reflect.type(loader, base + "NotifViewRenderer");
        Method committed = Reflect.method(manager, "dispatchOnAfterRenderEntries", void.class, renderer, List.class);
        feature.module.hook(committed).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
            Object result = chain.proceed();
            feature.collectionRendered();
            return result;
        });

        Class<?> builder = Reflect.type(loader, base + "NodeSpecBuilder");
        Class<?> spec = Reflect.type(loader, base + "NodeSpec");
        Class<?> node = Reflect.type(loader, base + "NodeController");
        Class<?> media = Reflect.type(loader, base + "MediaContainerController");
        Class<?> custom = Reflect.type(loader,
                "com.oplus.systemui.statusbar.notification.customcard.OplusCustomRowController");
        Method children = Reflect.method(spec, "getChildren", List.class);
        Method controller = Reflect.method(spec, "getController", node);
        Method build = Reflect.method(builder, "buildNodeSpec", spec, node, List.class, java.util.Map.class);
        collectionEntry = new NativeNotificationCollectionEntry(feature, loader, spec, node);
        feature.module.hook(build).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
            Object result = chain.proceed();
            List<?> nodes = (List<?>) Reflect.call(children, result);
            if (feature.collectionPageShowing()) {
                collectionEntry.restoreEmptyShade();
                nodes.removeIf(child -> {
                    Object owner = Reflect.call(controller, child);
                    return media.isInstance(owner) || custom.isInstance(owner);
                });
            }
            else {
                boolean ready = false;
                if (shouldCollect()) try {
                    var packages = feature.store.entries().reversed().stream().filter(item -> ready(item.key()))
                            .map(NotificationCloudStore.Entry::app)
                            .distinct().limit(NativeNotificationCollectionEntry.MAX_ICONS).toList();
                    ready = collectionEntry.append(chain.getArg(0), result, nodes, packages);
                } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
                    feature.failure("Notification collection row unavailable; originals retained", failure);
                }
                if (!ready) collectionEntry.restoreEmptyShade();
                if (grouping != ready) {
                    grouping = ready;
                    feature.main.post(() -> {
                        if (grouping) removeReplacedBanners(lastKeys);
                        invalidate();
                    });
                }
            }
            return result;
        });
    }
    private void deferInitialBanner(ClassLoader loader, Class<?> entry) throws ReflectiveOperationException {
        String name = "com.android.systemui.statusbar.notification.collection.coordinator.HeadsUpCoordinator";
        Class<?> coordinator = Reflect.type(loader, name);
        Class<?> pending = Reflect.type(loader, name + "$PostedEntry");
        Method item = Reflect.method(pending, "getEntry", entry);
        var binders = new java.util.LinkedHashSet<Method>();
        binders.add(Reflect.method(coordinator, "bindForAsyncHeadsUp", void.class, pending));
        try {
            Class<?> oplus = Reflect.type(loader, "com.oplus.systemui.statusbar.notification.collection.coordinator.OplusHeadsUpCoordinator");
            binders.add(Reflect.method(oplus, "bindForAsyncHeadsUp", void.class, pending));
        } catch (ReflectiveOperationException unavailable) { /* The AOSP coordinator remains available. */ }
        for (Method bind : binders) feature.module.hook(bind).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
            if (replaying || !feature.enabled() || !feature.rules().replaceHeadsUp()) return chain.proceed();
            Object posted = chain.getArg(0);
            Object current = Reflect.call(item, posted);
            StatusBarNotification source = current == null ? null : feature.notification(current);
            if (!feature.mayReplaceBanner(current, source)) return chain.proceed();
            String key = source.getKey();
            if (replaced(key)) return null;
            Object owner = chain.getThisObject();
            Object token = new Object();
            deferredBinds.put(key, token);
            feature.main.postDelayed(() -> {
                if (!deferredBinds.remove(key, token) || replaced(key) || !feature.unlocked()) return;
                Object live = feature.liveEntry(key);
                if (live != current || feature.notification(live) != source) return;
                replaying = true;
                try { Reflect.call(bind, owner, posted); }
                catch (RuntimeException | LinkageError failure) { feature.failure("Deferred notification banner unavailable", failure); }
                finally { replaying = false; }
            }, 1200);
            return null;
        });
    }
    private void installHeadsUp(ClassLoader loader, Class<?> entry) throws ReflectiveOperationException {
        Class<?> provider = Reflect.type(loader, "com.android.systemui.statusbar.notification.interruption.VisualInterruptionDecisionProviderImpl");
        Class<?> decision = Reflect.type(loader, provider.getName() + "$DecisionImpl");
        var ctor = decision.getDeclaredConstructor(boolean.class, String.class);
        ctor.setAccessible(true);
        Object suppressed = ctor.newInstance(false, "Converted notification card ready");
        Class<?> manager = Reflect.type(loader, "com.android.systemui.statusbar.notification.headsup.HeadsUpManager");
        FieldCapture capture = new FieldCapture(Reflect.uniqueField(provider, field -> Reflect.instance(field) && field.getType() == manager));
        removeHeadsUp = Reflect.method(manager, "removeNotification", boolean.class, String.class, boolean.class, String.class);
        for (Method method : provider.getDeclaredMethods()) if (method.getName().equals("makeUnloggedHeadsUpDecision")
                && java.util.Arrays.equals(method.getParameterTypes(), new Class<?>[]{entry})) feature.module.hook(method)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
                    headsUp = Reflect.get(capture.field, chain.getThisObject());
                    String key = feature.notification(chain.getArg(0)).getKey();
                    return replaced(key) ? suppressed : chain.proceed();
                });
    }
    private record FieldCapture(java.lang.reflect.Field field) { }
    void context(Context context) {
        context.getSystemService(NotificationManager.class).cancel(LEGACY_COLLECTION_TAG, LEGACY_COLLECTION_ID);
    }
    static boolean summary(StatusBarNotification sbn) { return ("com.android.systemui".equals(sbn.getPackageName())
            || dev.howard.colorosenhance.BuildConfig.PUBLIC_APPLICATION_ID.equals(sbn.getPackageName()))
            && LEGACY_COLLECTION_TAG.equals(sbn.getTag()) && sbn.getId() == LEGACY_COLLECTION_ID; }
    private boolean shouldCollect() {
        return collectionEntry != null && feature.collectionSupported() && feature.enabled() && feature.unlocked()
                && feature.rules().original() == NotificationCloudRules.Original.COLLECT;
    }
    private boolean ready(String key) {
        var item = feature.store.get(key);
        return feature.enabled() && feature.unlocked() && item != null
                && feature.store.ready(key);
    }
    boolean collected(String key) {
        return grouping && shouldCollect() && ready(key);
    }
    private boolean replaced(String key) {
        return feature.rules().original() == NotificationCloudRules.Original.HIDE && ready(key) || collected(key);
    }
    void refresh() {
        refresh(null);
    }
    void refresh(List<StatusBarNotification> live) {
        if (icons != null) {
            if (live == null) icons.reconcile(); else icons.reconcile(live);
        }
        java.util.Set<String> keys = feature.store.entries().stream().filter(entry -> ready(entry.key()))
                .map(dev.howard.colorosenhance.notifications.NotificationCloudStore.Entry::key).collect(java.util.stream.Collectors.toSet());
        if (lastKeys.equals(keys) && lastOriginal == feature.rules().original()) return;
        lastKeys = java.util.Set.copyOf(keys); lastOriginal = feature.rules().original();
        if (keys.isEmpty() || !shouldCollect()) grouping = false;
        queueRebuild();
        removeReplacedBanners(keys);
    }
    private void removeReplacedBanners(java.util.Set<String> keys) {
        if (!feature.rules().replaceHeadsUp() || headsUp == null || removeHeadsUp == null) return;
        for (String key : keys) if (replaced(key)) Reflect.call(removeHeadsUp, headsUp, key, false, "Converted notification ready");
    }
    void invalidate() {
        if (icons != null) icons.reconcile();
        queueRebuild();
    }
    private void queueRebuild() {
        if (builder != null && !rebuildQueued) rebuildQueued = feature.main.post(rebuildShade);
    }
    private void rebuildShade() {
        rebuildQueued = false;
        Reflect.call(rebuild, builder, 9);
    }
    void clear() {
        if (icons != null) icons.reconcile();
        if (grouping || !lastKeys.isEmpty()) {
            grouping = false;
            lastKeys = java.util.Set.of(); lastOriginal = null;
            queueRebuild();
        }
    }
}
