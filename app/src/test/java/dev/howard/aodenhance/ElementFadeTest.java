package dev.howard.aodenhance;

import org.junit.Test;
import static org.junit.Assert.*;

public class ElementFadeTest {
    @Test public void fadeOutKeepsDrawingUntilItsLastFrame() {
        ElementFade fade = new ElementFade();
        AlphaLedger.Clip clip = new AlphaLedger.Clip(2, 3, 40, 50);
        assertEquals(ElementFade.Change.ANIMATE, fade.retarget(true, true));
        fade.advance(.5f);
        assertEquals(.5f, fade.opacity(), 0);
        assertEquals(clip, fade.clip(clip));
        fade.advance(1);
        assertEquals(AlphaLedger.EMPTY, fade.clip(clip));
        assertFalse(fade.isAnimating());
    }

    @Test public void wakeRevealsLatestClipStartingAtZeroOpacity() {
        ElementFade fade = new ElementFade();
        fade.retarget(true, false);
        AlphaLedger.Clip newest = new AlphaLedger.Clip(4, 6, 30, 60);
        fade.retarget(false, true);
        assertEquals(0, fade.opacity(), 0);
        assertEquals(newest, fade.clip(newest));
        fade.advance(.5f);
        assertEquals(.5f, fade.opacity(), 0);
        fade.advance(1);
        assertNull(fade.clip(null));
        assertEquals(1, fade.opacity(), 0);
    }

    @Test public void rapidReversalStartsAtCurrentOpacityAndShortensDuration() {
        ElementFade fade = new ElementFade();
        fade.retarget(true, true);
        fade.advance(.4f);
        assertEquals(ElementFade.Change.ANIMATE, fade.retarget(false, true));
        assertEquals(.6f, fade.opacity(), .0001f);
        assertEquals(112, fade.durationMillis());
        fade.advance(.5f);
        assertEquals(.8f, fade.opacity(), .0001f);
        fade.retarget(true, true);
        assertEquals(.8f, fade.opacity(), .0001f);
        fade.advance(1);
        assertEquals(AlphaLedger.EMPTY, fade.clip(null));
    }

    @Test public void repeatedTargetDoesNotRestartButDisabledAnimationsSettle() {
        ElementFade fade = new ElementFade();
        fade.retarget(true, true);
        fade.advance(.25f);
        assertEquals(ElementFade.Change.KEEP, fade.retarget(true, true));
        fade.advance(.5f);
        assertEquals(.5f, fade.opacity(), 0);
        assertEquals(ElementFade.Change.SNAP, fade.retarget(true, false));
        fade.advance(.1f); // a stale frame cannot revive a settled animation
        assertEquals(0, fade.opacity(), 0);
        assertEquals(ElementFade.Change.SNAP, fade.retarget(false, false));
        assertEquals(1, fade.opacity(), 0);
        assertFalse(fade.isAnimating());
    }

    @Test public void nativeTransitionChangesComposeWithoutAccumulating() {
        ElementFade fade = new ElementFade();
        fade.retarget(true, true);
        fade.advance(.5f);
        assertEquals(.4f, fade.compose(.8f), 0);
        assertEquals(.15f, fade.compose(.3f), 0);
        assertEquals(.15f, fade.compose(.3f), 0);
        fade.retarget(false, false);
        assertEquals(.3f, fade.compose(.3f), 0);
    }

    @Test public void onlyLockscreenAodTransitionsAnimate() {
        assertTrue(ElementFade.shouldAnimate("KEYGUARD", "PANORAMIC_AOD", true));
        assertFalse(ElementFade.shouldAnimate("UNLOCK", "PANORAMIC_AOD", true));
        assertTrue(ElementFade.shouldAnimate("PANORAMIC_AOD", "KEYGUARD", true));
        assertFalse(ElementFade.shouldAnimate("PANORAMIC_AOD", "KEYGUARD", false));
        assertFalse(ElementFade.shouldAnimate("PANORAMIC_AOD", "UNLOCK", true));
        assertFalse(ElementFade.shouldAnimate("PANORAMIC_AOD", "AOD_OFF", true));
        assertFalse(ElementFade.shouldAnimate("PANORAMIC_AOD", "PANORAMIC_AOD", true));
        assertFalse(ElementFade.shouldAnimate("UNKNOW_STATE", "PANORAMIC_AOD", true));
        assertFalse(ElementFade.shouldAnimate("OCCLUDED", "PANORAMIC_AOD", true));
    }

    @Test public void sleepingFromAnAppStartsHiddenButLaterLockscreenTransitionsStillFade() {
        ElementFade fade = new ElementFade();
        // Firmware requests animation even when starting from an unlocked app or the launcher.
        assertEquals(ElementFade.Change.SNAP, fade.retarget(true,
                ElementFade.shouldAnimate("UNLOCK", "PANORAMIC_AOD", true)));
        assertEquals(0, fade.compose(1), 0);
        assertEquals(AlphaLedger.EMPTY, fade.clip(null));
        assertFalse(fade.isAnimating());

        // Waking to the actual lockscreen must still fade those elements back in.
        assertEquals(ElementFade.Change.ANIMATE, fade.retarget(false,
                ElementFade.shouldAnimate("PANORAMIC_AOD", "KEYGUARD", true)));
        assertEquals(0, fade.opacity(), 0);
        assertNull(fade.clip(null));
        fade.advance(1);
        assertEquals(1, fade.opacity(), 0);
        assertEquals(ElementFade.Change.ANIMATE, fade.retarget(true,
                ElementFade.shouldAnimate("KEYGUARD", "PANORAMIC_AOD", true)));
        assertEquals(1, fade.opacity(), 0);
        fade.advance(1);
        assertEquals(0, fade.opacity(), 0);
    }
}
