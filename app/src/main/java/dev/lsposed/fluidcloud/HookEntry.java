package dev.lsposed.fluidcloud;

import android.util.Log;
import android.content.SharedPreferences;
import dev.howard.colorosenhance.diagnostics.DiagnosticPoint;
import dev.howard.colorosenhance.diagnostics.DiagnosticReporter;
import java.lang.reflect.Method;
import java.util.Map;
import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;

/** Modern libxposed entry. Scope metadata and runtime checks both restrict injection. */
public final class HookEntry extends XposedModule {
    private static final String SYSTEM_UI = "com.android.systemui";
    private boolean allowed;
    private boolean systemServer;
    private boolean initialized;
    private ColorOsAdapter adapter;
    private MediaPauseRetention pausedMusic;
    private MediaShadeCard mediaShade;
    private volatile boolean mediaShadeEnabled;
    private boolean sceneInstallAttempted;
    private CameraCardExpansion cameraCards;
    private CloudExtras extras;
    private volatile boolean keepCapsuleInForeground;
    MediaWaveFeature mediaWaves;
    NotificationCloudFeature notificationCloud;
    private NotificationServerAlerts notificationAlerts;
    volatile CloudConfig config = CloudConfig.from(Map.of());
    private SharedPreferences preferences;
    private SharedPreferences.OnSharedPreferenceChangeListener preferenceListener;

    @Override public void onModuleLoaded(ModuleLoadedParam param) {
        if (!dev.howard.colorosenhance.migration.PackageActivation.allowed(() -> getRemotePreferences("integration"))) {
            if (!param.isSystemServer() && SYSTEM_UI.equals(param.getProcessName())) {
                info("SystemUI scope loaded, but module activation is not ready; check initialization or migration");
            }
            return;
        }
        systemServer = param.isSystemServer();
        allowed = !systemServer && SYSTEM_UI.equals(param.getProcessName());
    }

    @Override public void onSystemServerStarting(SystemServerStartingParam param) {
        if (!systemServer) return;
        try {
            observePreferences();
            new CapsuleTokenFade(param.getClassLoader()).install(this);
            report(DiagnosticPoint.TOKEN_FADE, DiagnosticPoint.State.INSTALLED);
            info("Capsule token-fade exclusion installed");
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
            report(DiagnosticPoint.TOKEN_FADE, DiagnosticPoint.State.UNSUPPORTED);
            error("Capsule token-fade contract unavailable; native transitions retained",failure);
        }
        try {
            notificationAlerts = new NotificationServerAlerts(this, param.getClassLoader());
            notificationAlerts.update(cloudValues());
            report(DiagnosticPoint.NOTIFICATION_ALERTS, DiagnosticPoint.State.INSTALLED);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
            report(DiagnosticPoint.NOTIFICATION_ALERTS, DiagnosticPoint.State.UNSUPPORTED);
            error("Notification alert adapter unavailable; system sound and vibration retained", failure);
        }
    }

    @Override public void onPackageReady(PackageReadyParam param) {
        if (!allowed || initialized || !param.isFirstPackage() || !SYSTEM_UI.equals(param.getPackageName())) return;
        initialized = true;
        try {
            observePreferences();
            if (config.unbindStatusBar()) {
                sceneInstallAttempted = true;
                adapter = new ColorOsAdapter(this, param.getClassLoader());
                adapter.install();
                report(DiagnosticPoint.SCENES, DiagnosticPoint.State.INSTALLED);
            } else {
                report(DiagnosticPoint.SCENES, DiagnosticPoint.State.NATIVE);
                info("Status-bar scene hooks disabled by unbind switch");
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
            adapter = null;
            report(DiagnosticPoint.SCENES, DiagnosticPoint.State.UNSUPPORTED);
            error("Unsupported SystemUI adapter; native behavior retained", error);
        }
        try {
            MediaWaveFeature waves = new MediaWaveFeature(this, param.getClassLoader());
            waves.installLegacySource();
            mediaWaves = waves;
            report(DiagnosticPoint.MEDIA_WAVE, DiagnosticPoint.State.INSTALLED);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
            report(DiagnosticPoint.MEDIA_WAVE, DiagnosticPoint.State.UNSUPPORTED);
            error("Realtime media waveform unavailable; OEM animation retained", error);
        }
        try { notificationCloud = new NotificationCloudFeature(this, param.getClassLoader()); report(DiagnosticPoint.NOTIFICATION_CLOUD, DiagnosticPoint.State.INSTALLED); }
        catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
            report(DiagnosticPoint.NOTIFICATION_CLOUD, DiagnosticPoint.State.UNSUPPORTED);
            error("Notification cloud unavailable; original notifications retained", error);
        }
        try {
            installMediaSender(param.getClassLoader());
        } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
            report(DiagnosticPoint.MEDIA_SHADE, DiagnosticPoint.State.UNSUPPORTED);
            report(DiagnosticPoint.MEDIA_HOLD, DiagnosticPoint.State.UNSUPPORTED);
            error("Media sender unavailable; native media behavior retained", error);
        }
        try { installCapsuleClickTop(param.getClassLoader()); report(DiagnosticPoint.CLICK_TOP, DiagnosticPoint.State.INSTALLED); }
        catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
            report(DiagnosticPoint.CLICK_TOP, DiagnosticPoint.State.UNSUPPORTED);
            error("Capsule click-top fix unavailable; native click behavior retained", error);
        }
        try {
            cameraCards = new CameraCardExpansion(this, param.getClassLoader());
            cameraCards.install();
            report(DiagnosticPoint.CAMERA_CARD, DiagnosticPoint.State.INSTALLED);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
            report(DiagnosticPoint.CAMERA_CARD, DiagnosticPoint.State.UNSUPPORTED);
            cameraCards = null;
            error("Camera card expansion unavailable; native card behavior retained", error);
        }
        try { extras=new CloudExtras(this,param.getClassLoader());extras.install();report(DiagnosticPoint.CLOUD_EXTRAS, DiagnosticPoint.State.INSTALLED); }
        catch(ReflectiveOperationException|RuntimeException|LinkageError error){extras=null;report(DiagnosticPoint.CLOUD_EXTRAS, DiagnosticPoint.State.UNSUPPORTED);error("Optional capsule controls unavailable; native behavior retained",error);}
    }

    private void installCapsuleClickTop(ClassLoader loader) throws ReflectiveOperationException {
        Class<?> bar = Reflect.type(loader, "com.android.systemui.statusbar.phone.PhoneStatusBarView");
        Class<?> helper = Reflect.type(loader, "com.oplus.systemui.statusbar.util.StatusbarClickHelper");
        Class<?> extension = Reflect.type(loader, "com.oplus.systemui.statusbar.phone.PhoneStatusBarViewExImpl");
        Method dispatch = Reflect.method(bar, "dispatchTouchEvent", boolean.class, android.view.MotionEvent.class);
        Method click = Reflect.method(helper, "scrollToTop", void.class, android.view.MotionEvent.class);
        Method caller = Reflect.method(extension, "handleClick", void.class, android.view.MotionEvent.class);
        if (!deoptimize(caller)) throw new IllegalStateException("Click-top caller could not be deoptimized");
        CapsuleClickTopGuard guard = new CapsuleClickTopGuard();
        var clickHook = hook(click).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
            if (config.preventCapsuleClickTop() && guard.suppress((android.view.MotionEvent) chain.getArg(0))) return null;
            return chain.proceed();
        });
        try {
            hook(dispatch).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain ->
                    guard.dispatch((android.view.View) chain.getThisObject(), (android.view.MotionEvent) chain.getArg(0),
                            config.preventCapsuleClickTop(), chain::proceed));
        } catch (RuntimeException | LinkageError error) { clickHook.unhook(); throw error; }
        info("Native capsule click-top exclusion installed");
    }

    private void installMediaSender(ClassLoader loader) throws ReflectiveOperationException {
        Class<?> controller = Reflect.type(loader,
                "com.oplus.systemui.media.seedling.livealert.MediaLiveAlertController");
        Class<?> media = Reflect.type(loader,
                "com.oplus.systemui.seedlingservice.mediaControl.SeedlingMediaData");
        Method send = Reflect.method(controller, "sendLiveAlertData", boolean.class, String.class);
        MediaPauseRetention retention = null;
        MediaShadeCard shade = null;
        try {
            retention = new MediaPauseRetention(controller, media, MediaPauseRetention::liveRetainableSession,
                    failure -> error("Paused music refresh unavailable; using native media behavior", failure));
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
            error("Paused music retention unavailable; native media behavior retained", failure);
        }
        try { shade = new MediaShadeCard(this, controller, media, mediaShadeEnabled); }
        catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
            error("Media notification host unavailable; retaining OEM hosts", failure);
        }
        final MediaPauseRetention paused = retention;
        final MediaShadeCard shadeCard = shade;
        hook(send).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
            Object owner = chain.getThisObject();
            String action = (String) chain.getArg(0);
            MediaPauseRetention.SendState retained = null;
            if (paused != null) try {
                paused.trace(owner, "before", action);
                retained = paused.beforeSend(owner, action, config.retainPausedMusic());
            } catch (RuntimeException | LinkageError error) {
                error("Paused music state unavailable; using native media behavior", error);
            }
            Object overridden = shadeCard == null ? null : shadeCard.beforeSend(owner, action);
            boolean delivered = false;
            try {
                Object result = chain.proceed();
                delivered = Boolean.TRUE.equals(result);
                return result;
            }
            finally {
                if (shadeCard != null) shadeCard.afterSend(overridden, delivered);
                if (paused != null) try { paused.afterSend(owner, retained);
                    paused.trace(owner, "after", action); }
                catch (RuntimeException | LinkageError error) {
                    error("Paused music timeout cleanup unavailable", error);
                }
                if(mediaWaves!=null)try{mediaWaves.nativeMediaChanged(owner);}
                catch(RuntimeException|LinkageError error){error("Media waveform selection unavailable",error);}
            }
        });
        pausedMusic = paused;
        mediaShade = shadeCard;
        if (pausedMusic != null) pausedMusic.configurationChanged(config.retainPausedMusic());
        report(DiagnosticPoint.MEDIA_HOLD, pausedMusic == null ? DiagnosticPoint.State.UNSUPPORTED : DiagnosticPoint.State.INSTALLED);
        report(DiagnosticPoint.MEDIA_SHADE, mediaShade == null ? DiagnosticPoint.State.UNSUPPORTED : DiagnosticPoint.State.INSTALLED);
        if (mediaShade != null) try { mediaShade.installRowVisibility(loader); }
        catch (ReflectiveOperationException | RuntimeException | LinkageError unavailable) {
            report(DiagnosticPoint.MEDIA_SHADE_ROW, DiagnosticPoint.State.UNSUPPORTED);
            error("Media notification row contract unavailable; native row visibility retained", unavailable);
        }
    }

    private void observePreferences() {
        preferences = getRemotePreferences(CloudConfig.GROUP);
        preferenceListener = (prefs, key) -> reload();
        preferences.registerOnSharedPreferenceChangeListener(preferenceListener);
        reload();
    }

    private void reload() {
        Map<String, ?> values = Map.of();
        try {
            values = cloudValues();
            config = CloudConfig.from(values);
            keepCapsuleInForeground = Boolean.TRUE.equals(values.get(NativeCapsuleForeground.KEY));
            mediaShadeEnabled = config.enabled() && Boolean.TRUE.equals(values.get(MediaShadeCard.KEY));
        }
        catch (RuntimeException error) {
            config = CloudConfig.from(Map.of());
            keepCapsuleInForeground = false;
            mediaShadeEnabled = false;
            error("Configuration unavailable; native scene behavior retained", error);
        }
        // Keep notification restoration independent of optional rendering adapters.
        if (notificationCloud != null) notificationCloud.configurationChanged(values);
        if (notificationAlerts != null) notificationAlerts.update(values);
        if (adapter != null) {
            if (!config.unbindStatusBar()) { adapter.uninstall(); adapter = null; sceneInstallAttempted = false; report(DiagnosticPoint.SCENES, DiagnosticPoint.State.NATIVE); }
            else adapter.configurationChanged();
        } else if (allowed && config.unbindStatusBar() && !sceneInstallAttempted) report(DiagnosticPoint.SCENES, DiagnosticPoint.State.RESTART);
        if (mediaShade != null) mediaShade.configurationChanged(mediaShadeEnabled);
        if (pausedMusic != null) pausedMusic.configurationChanged(config.retainPausedMusic());
        if (cameraCards != null) cameraCards.configurationChanged();
        if (extras != null) extras.configurationChanged();
        if (mediaWaves != null) mediaWaves.configurationChanged();
    }

    Map<String, ?> cloudValues() { return preferences == null ? Map.of() : preferences.getAll(); }
    boolean keepCapsuleInForeground() { return keepCapsuleInForeground; }

    boolean mediaShadeActive() { return mediaShade != null && mediaShade.active(); }

    android.view.View mediaShadeRow(android.view.View content) {
        return mediaShade == null ? null : mediaShade.ownedRow(content);
    }

    boolean mediaWaveVisible(android.view.View view){
        if(adapter!=null){Boolean presented=adapter.mediaWaveVisibility(view);if(presented!=null)return presented;}
        return view.isShown() && view.getWindowVisibility()==android.view.View.VISIBLE;
    }
    void mediaWavePixels(android.view.View view){if(adapter!=null)adapter.mediaWavePixels(view);}

    void report(DiagnosticPoint point, DiagnosticPoint.State state) { DiagnosticReporter.report(point, state); }

    void info(String message) { log(Log.INFO, "FluidCloudUnbound", message); }
    void error(String message, Throwable error) { log(Log.ERROR, "FluidCloudUnbound", message, error); }
}
