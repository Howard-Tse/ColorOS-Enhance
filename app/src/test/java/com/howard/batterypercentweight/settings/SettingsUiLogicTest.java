package com.howard.batterypercentweight.settings;

import static org.junit.Assert.assertEquals;

import com.howard.batterypercentweight.TypographySettings;

import org.junit.Test;

import java.util.Locale;

public final class SettingsUiLogicTest {
    @Test public void buildsCompleteSettingsFromIndependentControls() {
        TypographySettings settings = SettingsUiLogic.fromProgresses(
                false,
                true, 90,
                true, 80,
                true, 0,
                false, 60);

        assertEquals(new TypographySettings(
                false,
                true, 1000,
                true, 140,
                true, -30,
                false, 30), settings);
    }

    @Test public void previewSizeUsesSelectedSizeOnlyWhenItApplies() {
        TypographySettings selected = new TypographySettings(
                true,
                true, 570,
                true, 125,
                false, 0,
                false, 0);
        TypographySettings globallyDisabled = new TypographySettings(
                false,
                true, 570,
                true, 125,
                false, 0,
                false, 0);

        assertEquals(80.0f, SettingsUiLogic.previewTextSizeSp(selected), 0.0f);
        assertEquals(64.0f, SettingsUiLogic.previewTextSizeSp(globallyDisabled), 0.0f);
    }

    @Test public void formatsTenthsInActualUserUnits() {
        assertEquals("-3.0", SettingsUiLogic.formatTenths(Locale.US, -30));
        assertEquals("10.0", SettingsUiLogic.formatTenths(Locale.US, 100));
        assertEquals("14.0", SettingsUiLogic.formatTenths(Locale.US, 140));
    }
}
