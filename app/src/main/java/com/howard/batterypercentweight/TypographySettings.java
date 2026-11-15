package com.howard.batterypercentweight;

public record TypographySettings(
        boolean enabled,
        boolean weightEnabled,
        int weight,
        boolean textSizeEnabled,
        int textSizeTenthsDp,
        boolean horizontalOffsetEnabled,
        int horizontalOffsetTenthsDp,
        boolean verticalOffsetEnabled,
        int verticalOffsetTenthsDp) {

    public TypographySettings {
        weight = WeightConfig.normalize(weight);
        textSizeTenthsDp = TextAdjustmentConfig.normalizeTextSize(textSizeTenthsDp);
        horizontalOffsetTenthsDp =
                TextAdjustmentConfig.normalizeOffset(horizontalOffsetTenthsDp);
        verticalOffsetTenthsDp =
                TextAdjustmentConfig.normalizeOffset(verticalOffsetTenthsDp);
    }

    public static TypographySettings defaults() {
        return new TypographySettings(
                false, false, WeightConfig.DEFAULT,
                false, TextAdjustmentConfig.TEXT_SIZE_DEFAULT,
                false, TextAdjustmentConfig.OFFSET_DEFAULT,
                false, TextAdjustmentConfig.OFFSET_DEFAULT);
    }

    public boolean appliesWeight() {
        return enabled && weightEnabled;
    }

    public boolean appliesTextSize() {
        return enabled && textSizeEnabled;
    }

    public boolean appliesHorizontalOffset() {
        return enabled && horizontalOffsetEnabled;
    }

    public boolean appliesVerticalOffset() {
        return enabled && verticalOffsetEnabled;
    }
}
