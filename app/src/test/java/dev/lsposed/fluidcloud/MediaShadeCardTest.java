package dev.lsposed.fluidcloud;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class MediaShadeCardTest {
    @Test public void currentPlayingMediaCanCreateItsFirstCapsule() {
        assertTrue(MediaShadeCard.hasCapsule(true, false, "current", "old"));
    }

    @Test public void pausedMediaRequiresItsOwnExistingCapsule() {
        assertTrue(MediaShadeCard.hasCapsule(false, true, "current", "current"));
        assertFalse(MediaShadeCard.hasCapsule(false, true, "current", "old"));
        assertFalse(MediaShadeCard.hasCapsule(false, false, "current", "current"));
        assertFalse(MediaShadeCard.hasCapsule(false, true, null, null));
    }
}
