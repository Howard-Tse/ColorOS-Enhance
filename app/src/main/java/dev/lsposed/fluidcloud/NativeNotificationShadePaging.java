package dev.lsposed.fluidcloud;

import android.content.Context;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import dalvik.system.PathClassLoader;
import dev.howard.colorosenhance.BuildConfig;
import io.github.libxposed.api.XposedInterface;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/** Uses the installed plugin's media layout, gesture arbitration, snap physics and moving dots. */
final class NativeNotificationShadePaging {
    private static final String PLUGIN = "com.oplus.systemui.plugins";
    private final NotificationCloudFeature feature;
    private final NativeNotificationCloud cloud;
    private final NativeNotificationPageTemplates templates;
    private final Context plugin;
    private final int layout, pagerId, dotsId, gap;
    private final Method entry, key, content, rowTranslation, swipeHelper;
    private final Method rowLongClick;
    private final Field manager, disallow, translatedViews;
    private final Method setAdapter, limit, transformer, current, scrollState, jump;
    private final Method count, selected, offset, replace, location, closeAdapter;
    private final Constructor<?> adapter, spacing;
    private final Map<ViewGroup, Host> hosts = new IdentityHashMap<>();
    private final List<XposedInterface.HookHandle> hooks = new ArrayList<>();

    NativeNotificationShadePaging(NotificationCloudFeature feature, NativeNotificationCloud cloud, Context system,
            Context plugin, ClassLoader loader) throws ReflectiveOperationException {
        this.feature = feature;
        this.cloud = cloud;
        this.plugin = plugin;
        Resources res = plugin.getResources();
        layout = id(res, "layout", "media_multi_card_section");
        pagerId = id(res, "id", "music_viewpager");
        dotsId = id(res, "id", "page_indicator");
        gap = res.getDimensionPixelSize(id(res, "dimen", "media_card_view_pager_item_spacing"));
        Class<?> pager = Reflect.type(loader, "androidx.viewpager2.widget.ViewPager2");
        Class<?> dots = Reflect.type(loader, "com.coui.appcompat.indicator.COUIPageIndicator");
        Class<?> nativeAdapter = Reflect.type(loader, "androidx.recyclerview.widget.RecyclerView$Adapter");
        setAdapter = Reflect.method(pager, "setAdapter", void.class, nativeAdapter);
        limit = Reflect.method(pager, "setOffscreenPageLimit", void.class, int.class);
        transformer = Reflect.uniqueMethod(pager, m -> m.getName().equals("setPageTransformer") && m.getParameterCount() == 1);
        spacing = mediaTransformer(loader, transformer.getParameterTypes()[0]);
        current = Reflect.method(pager, "getCurrentItem", int.class);
        scrollState = Reflect.method(pager, "getScrollState", int.class);
        jump = Reflect.method(pager, "c", void.class, int.class, boolean.class);
        count = Reflect.method(dots, "setDotsCount", void.class, int.class);
        selected = Reflect.method(dots, "setCurrentPosition", void.class, int.class);
        offset = Reflect.method(dots, "a", void.class, float.class, int.class);
        Class<?> bridge;
        try {
            String apk = system.getPackageManager().getApplicationInfo(BuildConfig.PUBLIC_APPLICATION_ID, 0).sourceDir;
            bridge = Reflect.type(new PathClassLoader(apk, loader), "dev.lsposed.fluidcloud.pager.GroupPageAdapter");
        } catch (PackageManager.NameNotFoundException missing) { throw new ClassNotFoundException("Group page adapter", missing); }
        if (!nativeAdapter.isAssignableFrom(bridge)) throw new ClassNotFoundException("Plugin RecyclerView adapter");
        adapter = bridge.getConstructor(View.class, BiConsumer.class, Consumer.class, Runnable.class, Consumer.class);
        replace = bridge.getMethod("replace", List.class);
        location = bridge.getMethod("location");
        closeAdapter = bridge.getMethod("close");
        ClassLoader systemLoader = system.getClassLoader();
        Class<?> row = Reflect.type(systemLoader, "com.oplus.systemui.statusbar.notification.customcard.OplusCustomRow");
        Class<?> model = Reflect.type(systemLoader, "com.android.systemui.plugins.custom.CustomRowEntry");
        entry = Reflect.method(row, "getEntry", model);
        key = Reflect.method(model, "getKey", String.class);
        content = Reflect.method(model, "getCustomView", View.class);
        rowTranslation = Reflect.method(row, "getTranslation", float.class);
        Method longClick = null;
        try { longClick = Reflect.method(row, "doLongClick", void.class, String.class); }
        catch (NoSuchMethodException unavailable) { /* Retain the firmware's View long-click fallback. */ }
        rowLongClick = longClick;
        manager = Reflect.field(row, "customManager", Object.class);
        translatedViews = Reflect.field(row, "mTranslateableViews", List.class);
        swipeHelper = manager.getType().getMethod("getSwipeHepler");
        disallow = Reflect.field(swipeHelper.getReturnType(), "requestDisallowInterceptTouchEvent", boolean.class);
        var rowLayout = row.getDeclaredMethod("onLayout", boolean.class, int.class, int.class, int.class, int.class);
        var intercept = row.getDeclaredMethod("requestDisallowInterceptTouchEvent", boolean.class);
        templates = new NativeNotificationPageTemplates(feature, loader);
        try {
            for (String name : List.of("childNeedsClipping", "getCustomClipPath")) {
                var clip = row.getDeclaredMethod(name, View.class);
                hooks.add(feature.module.hook(clip).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
                    Host host = hosts.get(chain.getThisObject());
                    return host != null && host.shell == chain.getArg(0) && host.mount != null
                            ? chain.proceed(new Object[]{host.body}) : chain.proceed();
                }));
            }
            hooks.add(feature.module.hook(rowLayout).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
                Object result = chain.proceed();
                observe((ViewGroup) chain.getThisObject());
                return result;
            }));
            hooks.add(feature.module.hook(intercept).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
                ViewGroup view = (ViewGroup) chain.getThisObject();
                Host host = hosts.get(view);
                if (host == null || host.shell == null) return chain.proceed();
                boolean requested = (boolean) chain.getArg(0);
                boolean block = requested && Math.abs((float) Reflect.call(rowTranslation, view)) < 1f;
                Object helper = Reflect.call(swipeHelper, Reflect.get(manager, view));
                if (helper != null) disallow.setBoolean(helper, block);
                return block == requested ? chain.proceed() : chain.proceed(new Object[]{false});
            }));
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) { close(); throw failure; }
    }

    private static int id(Resources res, String type, String name) {
        int value = res.getIdentifier(name, type, PLUGIN);
        if (value == 0) throw new Resources.NotFoundException(name);
        return value;
    }
    private static Constructor<?> mediaTransformer(ClassLoader loader, Class<?> contract) throws NoSuchMethodException {
        for (String owner : List.of("j1", "e1")) try {
            Class<?> section = Reflect.type(loader, PLUGIN + ".shared.template.section.media." + owner);
            var field = Reflect.uniqueField(section, f -> Reflect.instance(f) && contract.isAssignableFrom(f.getType()));
            return field.getType().getConstructor(int.class, int.class);
        } catch (ReflectiveOperationException ignored) { }
        throw new NoSuchMethodException("Media page spacing transformer");
    }
    private void observe(ViewGroup row) {
        Host host = hosts.get(row);
        if (host == null && feature.shadePages.isEmpty()) return;
        Object model = Reflect.call(entry, row);
        var page = model == null ? null : feature.shadePages.current((String) Reflect.call(key, model));
        if (page == null || page.locked() == feature.unlocked() || !row.isAttachedToWindow()
                || !(Reflect.call(content, model) instanceof FrameLayout body)
                || body.getParent() != row || !(row instanceof FrameLayout)) {
            if (host != null) host.close();
            return;
        }
        if (host != null && (host.body != body || host.page.locked() != page.locked()
                || !host.page.rowKey().equals(page.rowKey()))) { host.close(); host = null; }
        if (host == null) {
            host = new Host(row, body, page);
            hosts.put(row, host);
            row.addOnAttachStateChangeListener(host);
        }
        if (host.page == page && host.mount != null) return;
        host.page = page;
        host.schedule();
    }
    void refresh() { for (ViewGroup row : List.copyOf(hosts.keySet())) observe(row); }
    void close() {
        hooks.forEach(XposedInterface.HookHandle::unhook);
        for (Host host : List.copyOf(hosts.values())) host.close();
        templates.close();
    }

    private record Cell(String key, long revision, NativeNotificationPageTemplates.Template template) { }
    private final class Host implements View.OnAttachStateChangeListener {
        final ViewGroup row;
        final FrameLayout body;
        final Map<FrameLayout, Cell> cells = new IdentityHashMap<>();
        final Runnable syncTask = this::sync, commitTask = this::commit;
        final Runnable resizeTask = this::resize;
        final View.OnLayoutChangeListener measured = (v,l,t,r,b,ol,ot,or,ob) -> {
            if (r-l != or-ol || b-t != ob-ot) queueResize();
        };
        NotificationPagerMount mount;
        NotificationShadePages.Page page;
        ViewGroup shell, pager;
        View dots;
        Object prototype, bridge;
        List<String> keys = List.of();
        boolean queued, resizeQueued, disposed, failed, changing;
        float position;
        Host(ViewGroup row, FrameLayout body, NotificationShadePages.Page page) {
            this.row = row; this.body = body; this.page = page;
        }
        void schedule() {
            if (queued || disposed || failed) return;
            queued = true; body.post(syncTask);
        }
        @SuppressWarnings("unchecked")
        void sync() {
            queued = false;
            if (disposed || failed || !body.isAttachedToWindow()) return;
            changing = true;
            try {
                if (shell == null) {
                    prototype = templates.prototype(cloud.shadeWrappers(page.rowKey()), body);
                    if (prototype == null) return;
                    shell = (ViewGroup) LayoutInflater.from(plugin).inflate(layout, body, false);
                    pager = shell.findViewById(pagerId);
                    dots = shell.findViewById(dotsId);
                    Consumer<String> longPress = page.locked() ? null : this::longPress;
                    bridge = adapter.newInstance(pager.getChildAt(0), (BiConsumer<FrameLayout, String>) this::bind,
                            (Consumer<FrameLayout>) this::release, (Runnable) this::moved, longPress);
                    Reflect.call(setAdapter, pager, bridge);
                    Reflect.call(limit, pager, 3);
                    pager.setClipChildren(false);
                    Reflect.call(transformer, pager, spacing.newInstance(gap, 1));
                    dots.setVisibility(View.VISIBLE);
                }
                if (mount == null) {
                    if (body.getHeight() <= 0 || body.getVisibility() != View.VISIBLE) return;
                    mount = new NotificationPagerMount((FrameLayout) row, body, shell, (List<View>) Reflect.get(translatedViews, row));
                }
                List<String> ordered = page.members().reversed().stream().map(e -> e.key()).toList();
                if (!keys.equals(ordered)) {
                    keys = ordered;
                    position = page.number() - 1;
                    Reflect.call(count, dots, keys.size());
                    Reflect.call(replace, bridge, keys);
                    Reflect.call(jump, pager, page.number() - 1, false);
                    Reflect.call(selected, dots, page.number() - 1);
                }
                for (var holder : List.copyOf(cells.keySet())) {
                    Cell cell = cells.get(holder);
                    if (cell != null && keys.contains(cell.key())) bind(holder, cell.key());
                }
                queueResize();
            } catch (ReflectiveOperationException | RuntimeException | LinkageError error) { fail(error); }
            finally { changing = false; }
        }
        void queueResize() {
            if (!disposed && !failed && !resizeQueued) {
                resizeQueued = true;
                row.postOnAnimation(resizeTask);
            }
        }
        int pageHeight(int index) {
            if (index < 0 || index >= keys.size()) return 0;
            String wanted = keys.get(index);
            for (Cell cell : cells.values())
                if (cell.key().equals(wanted)) return cell.template().view().getHeight();
            return 0;
        }
        void resize() {
            resizeQueued = false;
            if (mount == null || disposed || failed) return;
            // The keyguard positions its clock and widgets relative to the measured row.
            // Even a template measurement arriving mid-swipe must wait for the settled page.
            if (page.locked() && !NotificationPagerMount.settled(position,
                    (int) Reflect.call(current, pager), (int) Reflect.call(scrollState, pager))) return;
            int index = (int) position;
            float fraction = position - index;
            mount.present(NotificationPagerMount.between(pageHeight(index),
                    fraction == 0f ? 0 : pageHeight(index + 1), fraction));
        }
        void bind(FrameLayout holder, String member) {
            if (disposed || failed) return;
            int pos = keys.indexOf(member);
            if (pos < 0) { release(holder); return; }
            var target = page.at(pos);
            Cell old = cells.get(holder);
            if (old != null && old.key().equals(member) && old.revision() == target.member().revision()) return;
            boolean reuse = old != null && old.key().equals(member);
            if (!reuse) release(holder);
            try {
                var item = cloud.createPage(templates, prototype, target, reuse ? old.template() : null);
                cells.put(holder, new Cell(member, target.member().revision(), item));
                if (reuse) return;
                item.view().addOnLayoutChangeListener(measured);
                holder.addView(item.view(), new FrameLayout.LayoutParams(-1, -2));
                holder.setOnClickListener(v -> {
                    int index = keys.indexOf(member);
                    if (index >= 0 && feature.selectShadePage(page, index)) row.performClick();
                });
            } catch (ReflectiveOperationException | RuntimeException | LinkageError error) { fail(error); }
        }
        void longPress(String member) {
            if (disposed || failed || changing || page.locked() || !row.isShown()) return;
            int index = keys.indexOf(member);
            try {
                if (index < 0 || index != (int) Reflect.call(current, pager)
                        || (int) Reflect.call(scrollState, pager) != 0 || !feature.selectShadePage(page, index)) return;
                // Same entry point as OplusSwipeHelperExImpl.doLongClickCallback. The row
                // owns haptics, cancellation of the current touch and the ColorOS popup.
                if (rowLongClick != null) {
                    row.sendAccessibilityEvent(android.view.accessibility.AccessibilityEvent.TYPE_VIEW_LONG_CLICKED);
                    Reflect.call(rowLongClick, row, (Object) null);
                } else row.performLongClick();
            } catch (RuntimeException | LinkageError unavailable) {
                feature.failure("Grouped notification long press unavailable", unavailable);
            }
        }
        void moved() {
            if (disposed || failed || changing || keys.isEmpty() || bridge == null) return;
            float at = (float) Reflect.call(location, bridge);
            if (at >= 0) {
                at = Math.min(keys.size() - 1, at);
                position = at;
                if (!page.locked()) {
                    if (row.isInLayout()) queueResize();
                    else resize();
                }
                float fraction = at - (int) at;
                Reflect.call(offset, dots, fraction, (int) at);
                View pages = pager.getChildAt(0);
                float scale = mediaScale(fraction);
                if (pages.getScaleX() != scale) {
                    pages.setScaleX(scale);
                    pages.setScaleY(scale);
                }
            }
            // RecyclerView may deliver its final offset before ViewPager2 sets IDLE.
            // Check the authoritative selection after both scroll listeners have returned.
            if (at == Math.round(at) || (int) Reflect.call(scrollState, pager) == 0) {
                body.removeCallbacks(commitTask); body.post(commitTask);
            }
        }
        void commit() {
            if (disposed || failed || changing || (int) Reflect.call(scrollState, pager) != 0) return;
            int index = (int) Reflect.call(current, pager);
            if (index < 0 || index >= keys.size()) return;
            position = index;
            resize();
            Reflect.call(selected, dots, index);
            var live = feature.shadePages.current(page.rowKey());
            if (live != null && live.number() != index + 1 && feature.selectShadePage(page, index))
                page = feature.shadePages.current(page.rowKey());
        }
        void release(FrameLayout holder) {
            Cell old = cells.remove(holder);
            if (old == null) return;
            holder.setOnClickListener(null);
            old.template().view().removeOnLayoutChangeListener(measured);
            holder.removeView(old.template().view());
            templates.release(old.template());
        }
        void fail(Throwable error) {
            if (failed) return;
            failed = true;
            feature.failure("Notification media pager binding unavailable", error);
            body.post(this::unmount); // RecyclerView may be computing a layout.
        }
        void unmount() {
            row.removeCallbacks(resizeTask);
            if (bridge != null) {
                Reflect.call(closeAdapter, bridge);
                Reflect.call(setAdapter, pager, new Object[]{null});
                bridge = null;
            }
            for (FrameLayout host : List.copyOf(cells.keySet())) release(host);
            if (mount != null) { mount.remove(); mount = null; }
            shell = null;
        }
        void close() {
            if (disposed) return;
            disposed = true;
            hosts.remove(row, this);
            row.removeOnAttachStateChangeListener(this);
            body.removeCallbacks(syncTask); body.removeCallbacks(commitTask);
            unmount();
        }
        @Override public void onViewAttachedToWindow(View v) { }
        @Override public void onViewDetachedFromWindow(View v) { close(); }
    }

    // MediaPlayerMultiCardSection scales its scrolling child. Its adapter and stroke view
    // are tied to media sessions, but the same position curve also applies to notification pages.
    static float mediaScale(float fraction) {
        float edge = Math.abs(fraction - .5f) * 2f;
        return .8f + .2f * Math.max(0f, (edge - .7f) / .3f);
    }
}
