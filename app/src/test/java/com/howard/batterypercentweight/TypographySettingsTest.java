package com.howard.batterypercentweight;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

public final class TypographySettingsTest {
    @Test public void constructorNormalizesEveryNumericValue() {
        TypographySettings settings = new TypographySettings(
                true, true, 1007, true, 141, true, -31, true, 31);

        assertEquals(1000, settings.weight());
        assertEquals(140, settings.textSizeTenthsDp());
        assertEquals(-30, settings.horizontalOffsetTenthsDp());
        assertEquals(30, settings.verticalOffsetTenthsDp());
    }

    @Test public void globalSwitchGatesEveryProperty() {
        TypographySettings settings = new TypographySettings(
                false, true, 570, true, 100, true, 0, true, 0);

        assertFalse(settings.appliesWeight());
        assertFalse(settings.appliesTextSize());
        assertFalse(settings.appliesHorizontalOffset());
        assertFalse(settings.appliesVerticalOffset());
    }
}
