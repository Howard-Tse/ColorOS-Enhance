package com.howard.batterypercentweight.hook;

import com.howard.batterypercentweight.TextAdjustmentConfig;

final class PositionAdjustment {
    private PositionAdjustment() {}

    static float horizontal(float original, int tenthsDp, float density) {
        return original - TextAdjustmentConfig.toPx(tenthsDp, density);
    }

    static float vertical(float original, int tenthsDp, float density) {
        return original + TextAdjustmentConfig.toPx(tenthsDp, density);
    }
}
