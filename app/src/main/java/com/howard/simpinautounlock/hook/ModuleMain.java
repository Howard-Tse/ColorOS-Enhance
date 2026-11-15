package com.howard.simpinautounlock.hook;

import android.app.Application;
import android.content.SharedPreferences;
import com.howard.simpinautounlock.core.SimFeatureSettings;
import com.howard.simpinautounlock.core.PendingPinSource;
import com.howard.simpinautounlock.pending.RemotePendingPinRepositoryFactory;
import com.howard.simpinautounlock.pending.RepositoryPendingPinSource;
import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModuleInterface;

public final class ModuleMain extends XposedModule {
    private static final String TARGET = "com.android.phone";

    private SharedPreferences featurePreferences;
    private final SharedPreferences.OnSharedPreferenceChangeListener featureListener =
            (preferences, key) -> refreshFeatureSettings();

    private String processName = "";
    private PendingPinSource pendingPinSource = PendingPinSource.NONE;

    @Override
    public void onModuleLoaded(XposedModuleInterface.ModuleLoadedParam param) {
        if (!dev.howard.colorosenhance.migration.PackageActivation.allowed(() -> getRemotePreferences("integration"))) return;
        processName = param.getProcessName();
        SafeLog.attach(this, processName);
    }

    @Override
    public void onPackageLoaded(XposedModuleInterface.PackageLoadedParam param) {
        if (!TARGET.equals(processName) || !TARGET.equals(param.getPackageName())) {
            return;
        }
        initializeFeatureSettings();
        ClassLoader loader = param.getDefaultClassLoader();
        pendingPinSource = createRemotePendingPinSource();
        PinStateHookInstaller.install(this, loader);
        PinStorageHookInstaller.install(this, loader, pendingPinSource);
        PinResultHookInstaller.install(this, loader);
    }

    @Override
    public void onPackageReady(XposedModuleInterface.PackageReadyParam param) {
        if (!TARGET.equals(processName) || !TARGET.equals(param.getPackageName())) {
            return;
        }
        Application application = ReflectionAccess.currentApplication();
        if (application == null) {
            SafeLog.failure("PHONE_CONTEXT_UNAVAILABLE");
            return;
        }
        try {
            SafeLog.runtimeBoundary(PinStorageTraceEvent.PHONE_RUNTIME_INIT_EARLY);
            PhoneRuntime.initialize(application, param.getClassLoader(), pendingPinSource);
            SafeLog.runtimeBoundary(PinStorageTraceEvent.PHONE_RUNTIME_READY_EARLY);
        } catch (Throwable error) {
            SafeLog.failure("PHONE_RUNTIME_INIT_FAILED");
        }
    }

    private void initializeFeatureSettings() {
        try {
            if (featurePreferences == null) {
                featurePreferences = getRemotePreferences(SimFeatureSettings.GROUP);
                featurePreferences.registerOnSharedPreferenceChangeListener(featureListener);
            }
            refreshFeatureSettings();
        } catch (Throwable ignored) {
            applyFeatureSettings(SimFeatureSettings.OFF);
            SafeLog.failure("SIM_FEATURE_CONFIG_UNAVAILABLE");
        }
    }

    private synchronized void refreshFeatureSettings() {
        applyFeatureSettings(SimFeatureSettings.read(() -> featurePreferences.getAll()));
    }

    private void applyFeatureSettings(SimFeatureSettings settings) {
        SafeLog.featureState(settings.enabled(), settings.suppressPinFlash());
        PhoneRuntime.setFeatureEnabled(settings.enabled());
        PinStateHookInstaller.setSuppressionEnabled(settings.enabled() && settings.suppressPinFlash());
    }

    private PendingPinSource createRemotePendingPinSource() {
        try {
            if ((getFrameworkProperties() & XposedInterface.PROP_CAP_REMOTE) == 0) {
                SafeLog.failure("REMOTE_PREFERENCES_UNSUPPORTED");
                return PendingPinSource.NONE;
            }
            int channelVersion = getRemotePreferences(
                            RemotePendingPinRepositoryFactory.META_GROUP)
                    .getInt(RemotePendingPinRepositoryFactory.CHANNEL_VERSION_KEY, 0);
            if (channelVersion != RemotePendingPinRepositoryFactory.CHANNEL_VERSION) {
                SafeLog.failure("REMOTE_PREFERENCES_NOT_READY");
                return PendingPinSource.NONE;
            }
            PendingPinSource source = new RepositoryPendingPinSource(
                    RemotePendingPinRepositoryFactory.create(
                    getRemotePreferences(RemotePendingPinRepositoryFactory.DATA_GROUP),
                    getRemotePreferences(RemotePendingPinRepositoryFactory.KEY_GROUP)));
            SafeLog.hookInstalled("remote-preferences");
            return source;
        } catch (Throwable error) {
            SafeLog.failure("REMOTE_PREFERENCES_UNAVAILABLE");
            return PendingPinSource.NONE;
        }
    }
}
