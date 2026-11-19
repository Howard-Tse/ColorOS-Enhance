package dev.lsposed.fluidcloud;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class NativeMediaShadeTextTest {
    @Test public void fillsOnlyUpdatesSkippedByNativeUnlockedCollector() {
        assertTrue(NativeMediaShadeText.needsReplay(false, false, false, true));
        assertFalse(NativeMediaShadeText.needsReplay(true, false, false, true));
        assertFalse(NativeMediaShadeText.needsReplay(false, true, false, true));
    }

    @Test public void keepsHiddenMetadataCurrentWithoutRunningLyricTicksOffscreen() {
        assertTrue(NativeMediaShadeText.needsReplay(false, false, false, false));
        assertFalse(NativeMediaShadeText.needsReplay(false, false, true, false));
        assertTrue(NativeMediaShadeText.needsReplay(false, false, true, true));
    }
}
