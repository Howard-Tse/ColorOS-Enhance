package dev.howard.aodenhance;

import dev.howard.colorosenhance.BuildConfig;

import android.content.SharedPreferences;
import android.util.Log;
import io.github.libxposed.api.XposedModule;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public final class AodModule extends XposedModule {
    volatile Config config = Config.from(Map.of());
    private final Set<String> warned = ConcurrentHashMap.newKeySet();
    private SharedPreferences preferences;
    // SharedPreferences keeps listeners weakly; retain the callback for the process lifetime.
    private SharedPreferences.OnSharedPreferenceChangeListener preferenceListener;
    private ViewOverrides views;
    private boolean initialized;
    private boolean allowed;
    private final Map<String, Integer> traceCounts = new ConcurrentHashMap<>();

    @Override public void onModuleLoaded(ModuleLoadedParam param) {
        if (!dev.howard.colorosenhance.migration.PackageActivation.allowed(() -> getRemotePreferences("integration"))) return;
        allowed = !param.isSystemServer() && "com.android.systemui".equals(param.getProcessName());
    }
    @Override public void onPackageReady(PackageReadyParam param) {
        if (!allowed || initialized || !"com.android.systemui".equals(param.getPackageName()) || !param.isFirstPackage()) return;
        initialized = true;
        try {
            views = new ViewOverrides(this);
            preferences = getRemotePreferences(Config.GROUP);
            preferenceListener = (prefs, key) -> reload();
            preferences.registerOnSharedPreferenceChangeListener(preferenceListener);
            reload();
            new FirmwareHooks(this, param.getClassLoader(), views).install();
        } catch (Throwable error) { warnOnce("初始化失败；未加载的功能保持系统行为", error); }
    }
    private void reload() {
        try { config = Config.from(preferences.getAll()); }
        catch (RuntimeException error) { config = Config.from(Map.of()); warnOnce("配置读取失败", error); }
        traceCounts.clear();
        if (BuildConfig.DEBUG) info("配置 enabled=" + config.enabled + " blockTap=" + config.blockTap
                + " brightness=" + config.brightness + " scale=" + config.scale + " floor=" + config.floor
                + " dim=" + config.dim + " battery=" + config.hideBattery + " carrier=" + config.hideCarrier
                + " widgets=" + config.hideWidgets + " date=" + config.hideDate + " week=" + config.hideWeek + " lunar=" + config.hideLunar);
        if (views != null) views.refresh();
    }
    // Bound each diagnostic category to 20 events per configuration change/process.
    void trace(String category, String message) {
        if (!BuildConfig.DEBUG) return;
        if (traceCounts.compute(category, (key, count) -> count == null ? 1 : Math.min(21, count + 1)) <= 20)
            info(category + " " + message);
    }
    void info(String message) { log(Log.INFO, "AodEnhance", message); }
    void warnOnce(String feature, Throwable error) {
        if (warned.add(feature)) log(Log.WARN, "AodEnhance", feature, error);
    }
}
