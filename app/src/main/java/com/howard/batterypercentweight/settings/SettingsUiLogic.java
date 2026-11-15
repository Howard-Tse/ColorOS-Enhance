package com.howard.batterypercentweight.settings;

import com.howard.batterypercentweight.TextAdjustmentConfig;
import com.howard.batterypercentweight.TypographySettings;
import com.howard.batterypercentweight.WeightConfig;

import java.util.Locale;

public final class SettingsUiLogic {
    private static final float PREVIEW_DEFAULT_SP = 64.0f;

    private SettingsUiLogic() {
    }

    public static TypographySettings fromProgresses(
            boolean enabled,
            boolean weightEnabled,
            int weightProgress,
            boolean textSizeEnabled,
            int textSizeProgress,
            boolean horizontalOffsetEnabled,
            int horizontalOffsetProgress,
            boolean verticalOffsetEnabled,
            int verticalOffsetProgress) {
        return new TypographySettings(
                enabled,
                weightEnabled,
                WeightConfig.fromProgress(weightProgress),
                textSizeEnabled,
                TextAdjustmentConfig.textSizeFromProgress(textSizeProgress),
                horizontalOffsetEnabled,
                TextAdjustmentConfig.offsetFromProgress(horizontalOffsetProgress),
                verticalOffsetEnabled,
                TextAdjustmentConfig.offsetFromProgress(verticalOffsetProgress));
    }

    public static float previewTextSizeSp(TypographySettings settings) {
        return settings.appliesTextSize()
                ? PREVIEW_DEFAULT_SP * settings.textSizeTenthsDp()
                        / TextAdjustmentConfig.TEXT_SIZE_DEFAULT
                : PREVIEW_DEFAULT_SP;
    }

    public static String formatTenths(Locale locale, int valueTenths) {
        return String.format(locale, "%.1f", valueTenths / 10.0f);
    }
}
