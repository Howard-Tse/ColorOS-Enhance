package com.howard.batterypercentweight;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class PreferenceKeysTest {
    @Test public void recognizesEveryTypographyKeyAndNullRefresh() {
        assertTrue(PreferenceKeys.isManaged(null));
        assertTrue(PreferenceKeys.isManaged(PreferenceKeys.ENABLED));
        assertTrue(PreferenceKeys.isManaged(PreferenceKeys.WEIGHT_ENABLED));
        assertTrue(PreferenceKeys.isManaged(PreferenceKeys.WEIGHT));
        assertTrue(PreferenceKeys.isManaged(PreferenceKeys.TEXT_SIZE_ENABLED));
        assertTrue(PreferenceKeys.isManaged(PreferenceKeys.TEXT_SIZE_TENTHS_DP));
        assertTrue(PreferenceKeys.isManaged(PreferenceKeys.HORIZONTAL_OFFSET_ENABLED));
        assertTrue(PreferenceKeys.isManaged(PreferenceKeys.HORIZONTAL_OFFSET_TENTHS_DP));
        assertTrue(PreferenceKeys.isManaged(PreferenceKeys.VERTICAL_OFFSET_ENABLED));
        assertTrue(PreferenceKeys.isManaged(PreferenceKeys.VERTICAL_OFFSET_TENTHS_DP));
        assertFalse(PreferenceKeys.isManaged("unrelated"));
    }
}
