package com.howard.batterypercentweight;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public final class TextAdjustmentConfigTest {
    @Test public void normalizesTextSizeAndOffsets() {
        assertEquals(60, TextAdjustmentConfig.normalizeTextSize(12));
        assertEquals(140, TextAdjustmentConfig.normalizeTextSize(999));
        assertEquals(-30, TextAdjustmentConfig.normalizeOffset(-99));
        assertEquals(30, TextAdjustmentConfig.normalizeOffset(99));
    }

    @Test public void convertsTenthsDpAndSliderProgress() {
        assertEquals(40.5f, TextAdjustmentConfig.toPx(135, 3.0f), 0.0001f);
        assertEquals(40, TextAdjustmentConfig.textSizeToProgress(100));
        assertEquals(100, TextAdjustmentConfig.textSizeFromProgress(40));
        assertEquals(30, TextAdjustmentConfig.offsetToProgress(0));
        assertEquals(0, TextAdjustmentConfig.offsetFromProgress(30));
    }
}
