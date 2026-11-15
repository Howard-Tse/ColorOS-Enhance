package dev.howard.aodenhance;

public final class AodPolicy {
    private AodPolicy() {}
    public static boolean blockSingleTap(Config config, boolean panoramic) {
        return config.enabled && config.blockTap && panoramic;
    }
    public static boolean supportGesture(Config config, boolean panoramic, int gesture, boolean nativeSupport) {
        return nativeSupport || (gesture == 1 && blockSingleTap(config, panoramic));
    }
    public static float[] brightness(float[] nativeTable, Config config) {
        if (!config.enabled || !config.brightness || nativeTable == null) return nativeTable;
        float[] adjusted = nativeTable.clone();
        for (int i = 0; i < adjusted.length; i++) {
            if (Float.isFinite(adjusted[i]) && adjusted[i] > 0) {
                // Firmware divides the table by 1.6 before converting nit to backlight.
                adjusted[i] = Math.max(adjusted[i] * (config.scaleEnabled ? config.scale : 100) / 100f,
                        config.floorEnabled ? config.floor * 1.6f : 0);
            }
        }
        return adjusted;
    }
    public static boolean hide(String element, Config c, boolean elementScope) {
        if (!c.enabled || !elementScope) return false;
        return switch (element) {
            case "status" -> c.hideStatus;
            case "battery" -> c.hideBattery;
            case "carrier" -> c.hideCarrier;
            case "widgets" -> c.hideWidgets;
            case "date" -> c.hideDate;
            case "week" -> c.hideWeek;
            case "lunar" -> c.hideLunar;
            default -> false;
        };
    }
    public static float alpha(String element, float nativeAlpha, Config c, boolean panoramic) {
        if (!c.enabled || !panoramic) return nativeAlpha;
        if ("background".equals(element) && c.dimEnabled && c.dim > 0 && Float.isFinite(nativeAlpha)) {
            return Math.max(0, Math.min(1, nativeAlpha + (1 - nativeAlpha) * c.dim / 100f));
        }
        return nativeAlpha;
    }
}
