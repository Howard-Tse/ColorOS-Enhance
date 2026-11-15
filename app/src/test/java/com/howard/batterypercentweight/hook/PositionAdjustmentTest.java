package com.howard.batterypercentweight.hook;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public final class PositionAdjustmentTest {
    @Test public void positiveHorizontalValueMovesRightByReducingSystemOffset() {
        assertEquals(0.2f, PositionAdjustment.horizontal(0.5f, 1, 3.0f), 0.0001f);
    }

    @Test public void negativeHorizontalValueMovesLeft() {
        assertEquals(0.8f, PositionAdjustment.horizontal(0.5f, -1, 3.0f), 0.0001f);
    }

    @Test public void positiveVerticalValueMovesDown() {
        assertEquals(0.57f, PositionAdjustment.vertical(0.27f, 1, 3.0f), 0.0001f);
    }
}
