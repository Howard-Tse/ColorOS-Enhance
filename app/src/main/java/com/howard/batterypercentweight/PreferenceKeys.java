package com.howard.batterypercentweight;

import java.util.Set;

public final class PreferenceKeys {
    public static final String GROUP = "battery";
    public static final String ENABLED = "enabled";
    public static final String WEIGHT_ENABLED = "weight_enabled";
    public static final String WEIGHT = "weight";
    public static final String TEXT_SIZE_ENABLED = "text_size_enabled";
    public static final String TEXT_SIZE_TENTHS_DP = "text_size_tenths_dp";
    public static final String HORIZONTAL_OFFSET_ENABLED = "horizontal_offset_enabled";
    public static final String HORIZONTAL_OFFSET_TENTHS_DP = "horizontal_offset_tenths_dp";
    public static final String VERTICAL_OFFSET_ENABLED = "vertical_offset_enabled";
    public static final String VERTICAL_OFFSET_TENTHS_DP = "vertical_offset_tenths_dp";

    private static final Set<String> MANAGED_KEYS = Set.of(
            ENABLED,
            WEIGHT_ENABLED,
            WEIGHT,
            TEXT_SIZE_ENABLED,
            TEXT_SIZE_TENTHS_DP,
            HORIZONTAL_OFFSET_ENABLED,
            HORIZONTAL_OFFSET_TENTHS_DP,
            VERTICAL_OFFSET_ENABLED,
            VERTICAL_OFFSET_TENTHS_DP);

    private PreferenceKeys() {
    }

    public static boolean isManaged(String key) {
        return key == null || MANAGED_KEYS.contains(key);
    }
}
