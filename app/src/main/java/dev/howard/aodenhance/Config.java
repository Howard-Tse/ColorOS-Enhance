package dev.howard.aodenhance;

import java.util.Map;

/** Immutable, defensively decoded remote preferences. */
public final class Config {
    public static final String GROUP = "aod";
    public static final int SCALE_MIN = 75, SCALE_MAX = 500;
    public final boolean enabled, blockTap, brightness, hideStatus, hideBattery, hideCarrier, hideWidgets, hideDate, hideWeek, hideLunar;
    public final boolean scaleEnabled, floorEnabled, dimEnabled, animate;
    public final int scale, floor, dim;

    private Config(Map<String, ?> values) {
        enabled = flag(values, "enabled");
        blockTap = flag(values, "block_tap");
        scaleEnabled = flag(values, "scale_enabled");
        floorEnabled = flag(values, "floor_enabled");
        dimEnabled = flag(values, "dim_enabled");
        animate = !Boolean.FALSE.equals(values.get("animate"));
        brightness = scaleEnabled || floorEnabled;
        hideStatus = flag(values, "hide_status");
        hideBattery = flag(values, "hide_battery");
        hideCarrier = flag(values, "hide_carrier");
        hideWidgets = flag(values, "hide_widgets");
        hideDate = flag(values, "hide_date");
        hideWeek = flag(values, "hide_week");
        hideLunar = flag(values, "hide_lunar");
        scale = number(values, "scale", 100, SCALE_MIN, SCALE_MAX);
        floor = number(values, "floor", 0, 0, 20);
        dim = number(values, "dim", 0, 0, 100);
    }
    public static Config from(Map<String, ?> values) { return new Config(values); }
    private static boolean flag(Map<String, ?> values, String key) { return Boolean.TRUE.equals(values.get(key)); }
    private static int number(Map<String, ?> values, String key, int fallback, int min, int max) {
        Object value = values.get(key);
        return value instanceof Integer ? Math.max(min, Math.min(max, (Integer) value)) : fallback;
    }
}
