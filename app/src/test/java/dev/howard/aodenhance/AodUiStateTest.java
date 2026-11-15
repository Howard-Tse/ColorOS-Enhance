package dev.howard.aodenhance;

import java.util.Map;
import org.junit.Test;
import static org.junit.Assert.*;

public class AodUiStateTest {
    private final Config enabled = Config.from(Map.of(
            "enabled", true, "hide_widgets", true, "block_tap", true, "dim", 50));

    @Test public void fingerprintUnlockKeepsHiddenDrawingButEndsOtherAodOverrides() {
        AodUiState state = new AodUiState();
        ElementFade fade = new ElementFade();
        state.update("PANORAMIC_AOD");
        fade.retarget(AodPolicy.hide("widgets", enabled, state.hideElements()), false);
        state.update("UNLOCK");
        fade.retarget(AodPolicy.hide("widgets", enabled, state.hideElements()), false);

        assertFalse(state.isPanoramic());
        assertEquals("UNLOCK", state.name());
        assertEquals(0, fade.opacity(), 0);
        assertEquals(AlphaLedger.EMPTY, fade.clip(null));
        assertFalse(AodPolicy.blockSingleTap(enabled, state.isPanoramic()));
        assertEquals(.3f, AodPolicy.alpha("background", .3f, enabled, state.isPanoramic()), 0);
    }

    @Test public void repeatedUnlockAndRecreatedViewsCannotRevealHiddenElements() {
        AodUiState state = new AodUiState();
        state.update("PANORAMIC_AOD");
        state.update("UNLOCK");
        assertFalse(state.update("UNLOCK"));
        ElementFade newView = new ElementFade();
        newView.retarget(AodPolicy.hide("widgets", enabled, state.hideElements()), false);
        assertEquals(AlphaLedger.EMPTY, newView.clip(null));
        assertEquals(0, newView.opacity(), 0);
    }

    @Test public void wakingToLockscreenStillFadesInAndRestoresNativeClip() {
        AodUiState state = new AodUiState();
        state.update("PANORAMIC_AOD");
        ElementFade fade = new ElementFade();
        fade.retarget(true, false);
        String previous = state.name();
        state.update("KEYGUARD");
        assertFalse(state.hideElements());
        assertEquals(ElementFade.Change.ANIMATE, fade.retarget(
                AodPolicy.hide("widgets", enabled, state.hideElements()),
                ElementFade.shouldAnimate(previous, state.name(), true)));
        AlphaLedger.Clip nativeClip = new AlphaLedger.Clip(2, 3, 40, 50);
        assertEquals(nativeClip, fade.clip(nativeClip));
        assertEquals(0, fade.opacity(), 0);
        fade.advance(1);
        assertEquals(1, fade.opacity(), 0);
    }

    @Test public void nextLockscreenAndOtherAodModesClearUnlockRetention() {
        AodUiState state = new AodUiState();
        state.update("PANORAMIC_AOD");
        state.update("UNLOCK");
        state.update("KEYGUARD");
        assertFalse(state.hideElements());
        state.update("UNLOCK"); // Ordinary lockscreen unlock must not create retention.
        assertFalse(state.hideElements());
        assertFalse(ElementFade.shouldAnimate(state.name(), "PANORAMIC_AOD", true));
        state.update("PANORAMIC_AOD");
        assertTrue(state.hideElements());
        state.update("UNLOCK");
        state.update("NORMAL_AOD");
        assertFalse(state.hideElements());
    }

    @Test public void disablingDuringUnlockRestoresLatestNativeValues() {
        AodUiState state = new AodUiState();
        state.update("PANORAMIC_AOD");
        state.update("UNLOCK");
        assertTrue(state.hideElements());
        ElementFade fade = new ElementFade();
        fade.retarget(AodPolicy.hide("widgets", enabled, state.hideElements()), false);
        AlphaLedger.Clip newestClip = new AlphaLedger.Clip(4, 6, 80, 90);
        Config disabled = Config.from(Map.of("hide_widgets", true));
        fade.retarget(AodPolicy.hide("widgets", disabled, state.hideElements()), false);
        assertEquals(newestClip, fade.clip(newestClip));
        assertEquals(.4f, fade.compose(.4f), 0);
    }
}
