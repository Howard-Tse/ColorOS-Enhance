package com.howard.batterypercentweight.hook;

import android.content.SharedPreferences;
import android.util.Log;

import com.howard.batterypercentweight.PreferenceKeys;

import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam;

public final class BatteryWeightModule extends XposedModule
        implements SharedPreferences.OnSharedPreferenceChangeListener {
    private static final String TAG = "BatteryPercentWeight";
    private static final String SYSTEM_UI_PACKAGE = "com.android.systemui";

    private BatteryHookInstaller installer;
    private boolean allowed;

    @Override public void onModuleLoaded(ModuleLoadedParam param) {
        if (!dev.howard.colorosenhance.migration.PackageActivation.allowed(() -> getRemotePreferences("integration"))) return;
        allowed = !param.isSystemServer() && SYSTEM_UI_PACKAGE.equals(param.getProcessName());
    }

    @Override
    public void onPackageReady(PackageReadyParam param) {
        if (!allowed || installer != null || !param.isFirstPackage()
                || !SYSTEM_UI_PACKAGE.equals(param.getPackageName())) {
            return;
        }

        try {
            SharedPreferences preferences = getRemotePreferences(PreferenceKeys.GROUP);
            installer = BatteryHookInstaller.create(
                    this, param.getClassLoader(), preferences);
            installer.install();
            preferences.registerOnSharedPreferenceChangeListener(this);
        } catch (RuntimeException | LinkageError error) {
            safeLog("Unable to initialize SystemUI battery hooks", error);
        }
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences preferences, String key) {
        if (!PreferenceKeys.isManaged(key)) {
            return;
        }
        BatteryHookInstaller currentInstaller = installer;
        if (currentInstaller == null) {
            return;
        }
        try {
            currentInstaller.refreshFromPreferences();
        } catch (RuntimeException | LinkageError error) {
            safeLog("Preference callback failed", error);
        }
    }

    private void safeLog(String message, Throwable error) {
        try {
            log(Log.ERROR, TAG, message, error);
        } catch (RuntimeException | LinkageError ignored) {
            // Logging must never destabilize SystemUI.
        }
    }
}
