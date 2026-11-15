package com.howard.batterypercentweight;

public final class TextAdjustmentConfig {
    public static final int TEXT_SIZE_MIN = 60;
    public static final int TEXT_SIZE_MAX = 140;
    public static final int TEXT_SIZE_DEFAULT = 100;
    public static final int OFFSET_MIN = -30;
    public static final int OFFSET_MAX = 30;
    public static final int OFFSET_DEFAULT = 0;

    private TextAdjustmentConfig() {
    }

    public static int normalizeTextSize(int raw) {
        return Math.max(TEXT_SIZE_MIN, Math.min(TEXT_SIZE_MAX, raw));
    }

    public static int normalizeOffset(int raw) {
        return Math.max(OFFSET_MIN, Math.min(OFFSET_MAX, raw));
    }

    public static int textSizeToProgress(int value) {
        return normalizeTextSize(value) - TEXT_SIZE_MIN;
    }

    public static int textSizeFromProgress(int progress) {
        return TEXT_SIZE_MIN + Math.max(
                0, Math.min(TEXT_SIZE_MAX - TEXT_SIZE_MIN, progress));
    }

    public static int offsetToProgress(int value) {
        return normalizeOffset(value) - OFFSET_MIN;
    }

    public static int offsetFromProgress(int progress) {
        return OFFSET_MIN + Math.max(0, Math.min(OFFSET_MAX - OFFSET_MIN, progress));
    }

    public static float toPx(int tenthsDp, float density) {
        return tenthsDp * density / 10.0f;
    }
}
