package com.howard.batterypercentweight;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public final class WeightConfigTest {
    @Test public void clampsExpandedWeightRange() {
        assertEquals(100, WeightConfig.normalize(1));
        assertEquals(1000, WeightConfig.normalize(1200));
    }

    @Test public void roundsToNearestTen() {
        assertEquals(560, WeightConfig.normalize(564));
        assertEquals(570, WeightConfig.normalize(565));
    }

    @Test public void convertsExpandedWeightSliderBothWays() {
        assertEquals(47, WeightConfig.toProgress(570));
        assertEquals(570, WeightConfig.fromProgress(47));
        assertEquals(1000, WeightConfig.fromProgress(999));
    }

    @Test public void formatsOpenTypeVariation() {
        assertEquals("'wght' 570", WeightConfig.variationString(566));
    }
}
