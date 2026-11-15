package com.howard.batterypercentweight.settings;

import android.content.SharedPreferences;

import com.howard.batterypercentweight.PreferenceKeys;
import com.howard.batterypercentweight.TypographySettings;

import java.util.Objects;

import io.github.libxposed.service.XposedService;

public final class RemoteSettingsStore {
    private final PreferencePort preferences;

    public RemoteSettingsStore(XposedService service) {
        this(new SharedPreferencesPort(service));
    }

    public RemoteSettingsStore(PreferencePort preferences) {
        this.preferences = Objects.requireNonNull(preferences);
    }

    public TypographySettings read() {
        TypographySettings defaults = TypographySettings.defaults();
        return new TypographySettings(
                preferences.getBoolean(PreferenceKeys.ENABLED, defaults.enabled()),
                preferences.getBoolean(PreferenceKeys.WEIGHT_ENABLED, defaults.weightEnabled()),
                preferences.getInt(PreferenceKeys.WEIGHT, defaults.weight()),
                preferences.getBoolean(PreferenceKeys.TEXT_SIZE_ENABLED, defaults.textSizeEnabled()),
                preferences.getInt(PreferenceKeys.TEXT_SIZE_TENTHS_DP, defaults.textSizeTenthsDp()),
                preferences.getBoolean(
                        PreferenceKeys.HORIZONTAL_OFFSET_ENABLED,
                        defaults.horizontalOffsetEnabled()),
                preferences.getInt(
                        PreferenceKeys.HORIZONTAL_OFFSET_TENTHS_DP,
                        defaults.horizontalOffsetTenthsDp()),
                preferences.getBoolean(
                        PreferenceKeys.VERTICAL_OFFSET_ENABLED,
                        defaults.verticalOffsetEnabled()),
                preferences.getInt(
                        PreferenceKeys.VERTICAL_OFFSET_TENTHS_DP,
                        defaults.verticalOffsetTenthsDp()));
    }

    public boolean commit(
            TypographySettings requested,
            TypographySettings lastCommitted) {
        Objects.requireNonNull(requested);
        Objects.requireNonNull(lastCommitted);
        try {
            if (preferences.write(requested)) {
                return true;
            }
        } catch (RuntimeException | LinkageError ignored) {
            // The RemotePreferences cache may already contain the requested record.
        }
        try {
            preferences.write(lastCommitted);
        } catch (RuntimeException | LinkageError ignored) {
            // Compensation is best effort and must not crash the settings UI.
        }
        return false;
    }
}

interface PreferencePort {
    boolean getBoolean(String key, boolean fallback);

    int getInt(String key, int fallback);

    boolean write(TypographySettings settings);
}

final class SharedPreferencesPort implements PreferencePort {
    private final SharedPreferences preferences;

    SharedPreferencesPort(XposedService service) {
        preferences = Objects.requireNonNull(service)
                .getRemotePreferences(PreferenceKeys.GROUP);
    }

    @Override public boolean getBoolean(String key, boolean fallback) {
        return preferences.getBoolean(key, fallback);
    }

    @Override public int getInt(String key, int fallback) {
        return preferences.getInt(key, fallback);
    }

    @Override public boolean write(TypographySettings settings) {
        return preferences.edit()
                .putBoolean(PreferenceKeys.ENABLED, settings.enabled())
                .putBoolean(PreferenceKeys.WEIGHT_ENABLED, settings.weightEnabled())
                .putInt(PreferenceKeys.WEIGHT, settings.weight())
                .putBoolean(PreferenceKeys.TEXT_SIZE_ENABLED, settings.textSizeEnabled())
                .putInt(PreferenceKeys.TEXT_SIZE_TENTHS_DP, settings.textSizeTenthsDp())
                .putBoolean(
                        PreferenceKeys.HORIZONTAL_OFFSET_ENABLED,
                        settings.horizontalOffsetEnabled())
                .putInt(
                        PreferenceKeys.HORIZONTAL_OFFSET_TENTHS_DP,
                        settings.horizontalOffsetTenthsDp())
                .putBoolean(
                        PreferenceKeys.VERTICAL_OFFSET_ENABLED,
                        settings.verticalOffsetEnabled())
                .putInt(
                        PreferenceKeys.VERTICAL_OFFSET_TENTHS_DP,
                        settings.verticalOffsetTenthsDp())
                .commit();
    }
}
