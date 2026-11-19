package dev.lsposed.fluidcloud;

import android.app.KeyguardManager;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import dev.howard.colorosenhance.diagnostics.DiagnosticPoint;
import io.github.libxposed.api.XposedInterface;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Objects;

/** Temporarily admits a live OEM media card to the unlocked notification host. */
final class MediaShadeCard {
    static final String KEY = "media_card_in_shade";
    static final String SEND = "sendLiveAlert";
    private final HookEntry module;
    private final Method send, state, playerId, isPlayState;
    private final Field handler, context, model, media, visible, lastPlayer, hidden, statusBarAllowed, showingKeyguard;
    private final Object playbackStates;
    private volatile boolean enabled;
    private volatile WeakReference<Object> controller = new WeakReference<>(null);
    private volatile DiagnosticPoint.State lastReport;
    private volatile boolean capsuleAvailable;
    private DiagnosticPoint.State lastRowReport;
    private final Handler main = new Handler(Looper.getMainLooper());
    private NativeRows rows;

    MediaShadeCard(HookEntry module, Class<?> owner, Class<?> item, boolean enabled) throws ReflectiveOperationException {
        this.module = module;
        this.enabled = enabled;
        Class<?> data = Reflect.type(owner.getClassLoader(), "com.oplus.systemui.media.livealert.MediaLiveAlertData");
        send = Reflect.method(owner, "sendLiveAlertData", boolean.class, String.class);
        handler = Reflect.field(owner, "myHandler", Handler.class);
        context = Reflect.field(owner, "sysContext", Context.class);
        model = Reflect.field(owner, "mediaLiveAlertData", data);
        media = Reflect.field(owner, "mediaDataList", List.class);
        visible = Reflect.field(owner, "showOnStatusBar", boolean.class);
        lastPlayer = Reflect.field(owner, "lastStatusBarPlayerId", String.class);
        hidden = Reflect.field(data, "shouldNotificationSeedlingCardHidden", boolean.class);
        statusBarAllowed = Reflect.field(data, "isMediaSeedlingShowOnStatusBar", boolean.class);
        showingKeyguard = Reflect.field(data, "isShowKeyguard", boolean.class);
        state = Reflect.method(item, "getState", int.class);
        playerId = Reflect.method(item, "getPlayerId", String.class);
        playbackStates = Reflect.get(Reflect.field(item, "Companion", Object.class), null);
        isPlayState = Reflect.method(playbackStates.getClass(), "isPlayState", boolean.class, int.class);
        report(enabled ? DiagnosticPoint.State.WAITING : DiagnosticPoint.State.NATIVE);
    }

    Object beforeSend(Object owner, String action) {
        if (!SEND.equals(action)) { if ("cancelLiveAlert".equals(action)) setCapsuleAvailable(false); return null; }
        if (controller.get() != owner) controller = new WeakReference<>(owner);
        if (!enabled) return null;
        try {
            Object data = Reflect.get(model, owner);
            if (data == null || !(boolean) Reflect.get(statusBarAllowed, data) || (boolean) Reflect.get(showingKeyguard, data)) return unavailableCapsule();
            List<?> active = (List<?>) Reflect.get(media, owner);
            Object item = active.isEmpty() ? null : active.getFirst();
            if (item == null) { report(DiagnosticPoint.State.WAITING); return unavailableCapsule(); }
            boolean playing = (boolean) Reflect.call(isPlayState, playbackStates, Reflect.call(state, item));
            Object id = Reflect.call(playerId, item);
            boolean capsule = hasCapsule(playing, (boolean) Reflect.get(visible, owner), id, Reflect.get(lastPlayer, owner));
            if (!capsule) { report(DiagnosticPoint.State.WAITING); return unavailableCapsule(); }
            KeyguardManager lock = ((Context) Reflect.get(context, owner)).getSystemService(KeyguardManager.class);
            if (lock == null || lock.isKeyguardLocked()) return unavailableCapsule();
            setCapsuleAvailable(true);
            if (!(boolean) Reflect.get(hidden, data)) { report(DiagnosticPoint.State.NATIVE); return null; }
            hidden.setBoolean(data, false);
            return data;
        } catch (IllegalAccessException | RuntimeException | LinkageError failure) {
            report(DiagnosticPoint.State.FAILED);
            return unavailableCapsule();
        }
    }

    private Object unavailableCapsule() { setCapsuleAvailable(false); return null; }

    boolean active() { return enabled && capsuleAvailable; }

    View ownedRow(View content) {
        if (!active() || rows == null || Looper.myLooper() != Looper.getMainLooper()) return null;
        return rows.ownedRow(content);
    }

    private void setCapsuleAvailable(boolean value) {
        if (capsuleAvailable == value) return;
        capsuleAvailable = value;
        NativeRows current = rows;
        if (current != null) main.post(current::refresh);
    }

    void installRowVisibility(ClassLoader loader) throws ReflectiveOperationException {
        NativeRows nativeRows = new NativeRows(loader);
        nativeRows.install();
        rows = nativeRows;
        reportRow(DiagnosticPoint.State.INSTALLED);
        try { installRowAnimation(loader); }
        catch (ReflectiveOperationException | RuntimeException | LinkageError unavailable) {
            reportRow(DiagnosticPoint.State.PARTIAL);
        }
    }

    private final class NativeRows {
        private final Method update, initialize, controllers, getEntry, service, getView, getViewState, barMode, invalidate;
        private final Field barState, filter, gone;
        private WeakReference<Object> manager = new WeakReference<>(null);
        private WeakReference<View> managed = new WeakReference<>(null);

        NativeRows(ClassLoader loader) throws ReflectiveOperationException {
            String prefix = "com.oplus.systemui.statusbar.notification.customcard.";
            Class<?> owner = Reflect.type(loader, prefix + "OplusCustomRowControllerManager");
            Class<?> controller = Reflect.type(loader, prefix + "OplusCustomRowController");
            Class<?> view = Reflect.type(loader, prefix + "OplusCustomRow");
            Class<?> entry = Reflect.type(loader, "com.android.systemui.plugins.custom.CustomRowEntry");
            Class<?> plugin = Reflect.type(loader, "com.android.systemui.plugins.statusbar.SeedlingPlugin");
            Class<?> state = Reflect.type(loader, "com.android.systemui.plugins.statusbar.StatusBarStateController");
            Class<?> viewState = Reflect.type(loader, "com.android.systemui.statusbar.notification.stack.ExpandableViewState");
            update = Reflect.method(owner, "setCustomContainerControllerList", void.class, List.class, boolean.class);
            initialize = Reflect.method(owner, "initPlugin", void.class, plugin);
            controllers = Reflect.method(owner, "getOplusCustomRowControllerList", List.class);
            getEntry = Reflect.method(controller, "getEntry", entry);
            service = Reflect.method(entry, "getServiceId", String.class);
            getView = Reflect.method(controller, "getOplusCustomRow", view);
            getViewState = Reflect.method(view, "getViewState", viewState);
            gone = Reflect.field(viewState, "gone", boolean.class);
            barState = Reflect.field(owner, "statusBarStateController", state);
            barMode = Reflect.method(state, "getState", int.class);
            filter = Reflect.field(owner, "mNotifFilter", Object.class);
            invalidate = Reflect.method(filter.getType(), "invalidateList", void.class, String.class);
        }

        void install() {
            var capture = module.hook(initialize).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
                Object result = chain.proceed();
                manager = new WeakReference<>(chain.getThisObject());
                return result;
            });
            try {
                module.hook(update).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
                    Object result = chain.proceed();
                    Object owner = chain.getThisObject();
                    if (manager.get() != owner) manager = new WeakReference<>(owner);
                    if (enabled || managed.get() != null) refresh();
                    return result;
                });
            } catch (RuntimeException | LinkageError error) { capture.unhook(); throw error; }
        }

        void refresh() {
            if (Looper.myLooper() != Looper.getMainLooper()) { main.post(this::refresh); return; }
            Object owner = manager.get();
            if (owner == null) return;
            try {
                if ((int) Reflect.call(barMode, Reflect.get(barState, owner)) != 0) { managed.clear(); return; }
                if (enabled && capsuleAvailable) {
                    View row = mediaRow(owner);
                    if (row == null) { reportRow(DiagnosticPoint.State.WAITING); return; }
                    apply(owner, row, true);
                    if (managed.get() != row) managed = new WeakReference<>(row);
                    reportRow(DiagnosticPoint.State.ACTIVE);
                } else {
                    View previous = managed.get();
                    managed.clear();
                    if (previous != null) apply(owner, previous, false);
                    if (enabled) reportRow(DiagnosticPoint.State.WAITING);
                }
            } catch (IllegalAccessException | RuntimeException | LinkageError unavailable) { reportRow(DiagnosticPoint.State.FAILED); }
        }

        private View mediaRow(Object owner) {
            for (Object item : (List<?>) Reflect.call(controllers, owner)) {
                Object entry = Reflect.call(getEntry, item);
                if (entry != null && mediaService(Reflect.call(service, entry))) return (View) Reflect.call(getView, item);
            }
            return null;
        }

        View ownedRow(View content) {
            View row = managed.get();
            Object owner = manager.get();
            if (row == null || owner == null || !row.isAttachedToWindow()) return null;
            for (View node = content; node != null; node = node.getParent() instanceof View parent ? parent : null) {
                if (node == row) return (int) Reflect.call(barMode, Reflect.get(barState, owner)) == 0 ? row : null;
            }
            return null;
        }

        private void apply(Object owner, View row, boolean visible) throws IllegalAccessException {
            Object state = Reflect.call(getViewState, row);
            boolean stateChanged = state != null && gone.getBoolean(state) == visible;
            if (stateChanged) gone.setBoolean(state, !visible);
            int value = visible ? View.VISIBLE : View.GONE;
            boolean viewChanged = row.getVisibility() != value;
            if (viewChanged) row.setVisibility(value);
            if (stateChanged || viewChanged) Reflect.call(invalidate, Reflect.get(filter, owner), "media cloud shade visibility");
        }
    }

    private static boolean mediaService(Object id) {
        return "268452006".equals(id) || "default_com.android.systemui_268452006".equals(id);
    }

    private void installRowAnimation(ClassLoader loader) throws ReflectiveOperationException {
        Class<?> row = Reflect.type(loader, "com.oplus.systemui.statusbar.notification.customcard.OplusCustomRow");
        Class<?> entry = Reflect.type(loader, "com.android.systemui.plugins.custom.CustomRowEntry");
        Field entryField = Reflect.field(row, "entry", entry);
        Method service = Reflect.method(entry, "getServiceId", String.class);
        Method animation = Reflect.method(row, "setExpandAnimationRunning", void.class, boolean.class);
        module.hook(animation).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
            Object result = chain.proceed();
            if (!enabled || !capsuleAvailable || (boolean) chain.getArg(0)) return result;
            View view = (View) chain.getThisObject();
            if (view.getVisibility() != View.GONE) return result;
            Object rowEntry = Reflect.get(entryField, view);
            Object id = rowEntry == null ? null : Reflect.call(service, rowEntry);
            if (mediaService(id)) {
                KeyguardManager lock = view.getContext().getSystemService(KeyguardManager.class);
                if (lock != null && !lock.isKeyguardLocked()) view.setVisibility(View.VISIBLE);
            }
            return result;
        });
    }

    static boolean hasCapsule(boolean playing, boolean visible, Object player, Object lastPlayer) {
        return playing || (visible && player != null && Objects.equals(player, lastPlayer));
    }

    void afterSend(Object data, boolean delivered) {
        if (data == null) return;
        try {
            hidden.setBoolean(data, true);
            report(delivered ? DiagnosticPoint.State.ACTIVE : DiagnosticPoint.State.FAILED);
        } catch (IllegalAccessException | RuntimeException | LinkageError failure) {
            report(DiagnosticPoint.State.FAILED);
        }
    }

    void configurationChanged(boolean value) {
        if (value == enabled) return;
        enabled = value;
        if (!value) setCapsuleAvailable(false);
        report(value ? DiagnosticPoint.State.WAITING : DiagnosticPoint.State.NATIVE);
        Object owner = controller.get();
        if (owner == null) return;
        try {
            ((Handler) Reflect.get(handler, owner)).post(() -> {
                try { Reflect.call(send, owner, SEND); }
                catch (RuntimeException | LinkageError failure) { report(DiagnosticPoint.State.FAILED); }
            });
        } catch (RuntimeException | LinkageError failure) { report(DiagnosticPoint.State.FAILED); }
    }

    private void report(DiagnosticPoint.State state) {
        if (lastReport != state) {
            lastReport = state;
            module.report(DiagnosticPoint.MEDIA_SHADE_ROUTE, state);
        }
    }

    private void reportRow(DiagnosticPoint.State state) {
        if (lastRowReport != state) {
            lastRowReport = state;
            module.report(DiagnosticPoint.MEDIA_SHADE_ROW, state);
        }
    }
}
