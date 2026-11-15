package dev.howard.aodenhance;

import org.junit.Test;
import java.util.Map;
import static org.junit.Assert.*;

public class AodPolicyTest {
    @Test public void panoramicCallbackAcceptsDoubleTapEvenWithNativeDoubleTapDisabled() {
        Config c = Config.from(Map.of("enabled", true, "block_tap", true));
        assertTrue(AodPolicy.supportGesture(c, true, 1, false));
        assertTrue(AodPolicy.supportGesture(c, true, 16, true));
        assertFalse(AodPolicy.supportGesture(c, true, 2, false));
        assertFalse(AodPolicy.supportGesture(c, false, 1, false));
        assertFalse(AodPolicy.supportGesture(Config.from(Map.of()), true, 1, false));
        assertFalse(AodPolicy.supportGesture(Config.from(Map.of("enabled", true)), true, 1, false));
    }
    @Test public void colorOs17SwipeUpRetainsNativeSupportWithoutInventingIt() {
        Config c = Config.from(Map.of("enabled", true, "block_tap", true));
        assertTrue(AodPolicy.supportGesture(c, true, 23, true));
        assertTrue(AodPolicy.supportGesture(c, false, 23, true));
        assertFalse(AodPolicy.supportGesture(c, true, 23, false));
        assertTrue(AodPolicy.supportGesture(Config.from(Map.of()), true, 23, true));
    }
    @Test public void touchSingleTapOnlyConsumedInPanoramicAndWhenEnabled() {
        Config c = Config.from(Map.of("enabled", true, "block_tap", true));
        assertTrue(AodPolicy.blockSingleTap(c, true));
        assertFalse(AodPolicy.blockSingleTap(c, false));
        assertFalse(AodPolicy.blockSingleTap(Config.from(Map.of("block_tap", true)), true));
        assertFalse(AodPolicy.blockSingleTap(Config.from(Map.of("enabled", true)), true));
    }
    @Test public void brightnessUsesCopyAndNativeUnitsWithoutCompounding() {
        Config c = Config.from(Map.of("enabled", true, "scale_enabled", true, "floor_enabled", true, "scale", 120, "floor", 8));
        float[] source = {6, 42};
        assertArrayEquals(new float[]{12.8f, 50.4f}, AodPolicy.brightness(source, c), .001f);
        assertArrayEquals(new float[]{6, 42}, source, 0);
        assertArrayEquals(new float[]{12.8f, 50.4f}, AodPolicy.brightness(source, c), .001f);
    }
    @Test public void disabledMasterPreservesBrightnessAndElements() {
        Config c = Config.from(Map.of("scale_enabled", true, "floor_enabled", true, "scale", 150, "hide_status", true));
        float[] source = {6, 42};
        assertSame(source, AodPolicy.brightness(source, c));
        assertEquals(.6f, AodPolicy.alpha("status", .6f, c, true), 0);
    }
    @Test public void malformedPreferencesFailToSafeDefaultsAndClampRanges() {
        Config c = Config.from(Map.of("enabled", "true", "scale", 999, "floor", -2, "dim", 300));
        assertFalse(c.enabled);
        assertEquals(500, c.scale);
        assertEquals(0, c.floor);
        assertEquals(100, c.dim);
        assertEquals(100, Config.from(Map.of("scale", "bad")).scale);
    }
    @Test public void alphaIsScopedToPanoramicBackground() {
        Config c = Config.from(Map.of("enabled", true, "hide_status", true, "dim_enabled", true, "dim", 50));
        assertTrue(AodPolicy.hide("status", c, true));
        assertEquals(.8f, AodPolicy.alpha("status", .8f, c, false), 0);
        assertEquals(.5f, AodPolicy.alpha("background", .5f, c, false), 0);
        assertEquals(.75f, AodPolicy.alpha("background", .5f, c, true), 0);
        assertEquals(1, AodPolicy.alpha("background", 1, c, true), 0);
    }
    @Test public void ledgerRestoresLatestNativeRequestAcrossRepeatedEntry() {
        AlphaLedger<Object> ledger = new AlphaLedger<>();
        Object view = new Object();
        ledger.register(view, "status", .8f);
        ledger.request(view, .35f);
        ledger.register(view, "status", 0); // rediscovery must not record our hidden alpha
        assertEquals(.35f, ledger.get(view).nativeAlpha, 0);
        Config c = Config.from(Map.of("enabled", true, "hide_status", true));
        assertTrue(AodPolicy.hide("status", c, true));
        assertEquals(.35f, AodPolicy.alpha("status", ledger.get(view).nativeAlpha, c, false), 0);
    }
    @Test public void hidingDoesNotChangeFirmwareAlphaBasedVisibilityDecision() {
        Config c = Config.from(Map.of("enabled", true, "hide_status", true));
        float alphaSeenByFirmware = AodPolicy.alpha("status", 1, c, true);
        assertTrue("native setVisibility(getAlpha()>0) must remain visible", alphaSeenByFirmware > 0);
    }
    @Test public void clipRestoresLatestNativeBoundsOnDisable() {
        AlphaLedger<Object> ledger = new AlphaLedger<>();
        Object key = new Object(); ledger.register(key, "status", 1);
        Config c = Config.from(Map.of("enabled", true, "hide_status", true));
        AlphaLedger.Entry entry = ledger.get(key);
        entry.fade.retarget(AodPolicy.hide(entry.element, c, true), false);
        assertEquals(AlphaLedger.EMPTY, entry.fade.clip(entry.nativeClip));
        AlphaLedger.Clip nativeClip = new AlphaLedger.Clip(2, 3, 40, 50);
        ledger.requestClip(key, nativeClip);
        assertEquals(AlphaLedger.EMPTY, entry.fade.clip(entry.nativeClip));
        entry.fade.retarget(AodPolicy.hide(entry.element, c, false), true);
        assertEquals(nativeClip, entry.fade.clip(entry.nativeClip));
        ledger.requestClip(key, null);
        entry.fade.retarget(AodPolicy.hide(entry.element, Config.from(Map.of()), true), false);
        assertNull(entry.fade.clip(entry.nativeClip));
    }
    @Test public void removedLockPreferenceNoLongerHidesAnything() {
        Config c = Config.from(Map.of("enabled", true, "hide_lock", true));
        assertFalse(AodPolicy.hide("lock", c, true));
        assertFalse(AodPolicy.hide("widgets", c, true));
        assertFalse(AodPolicy.hide("date", c, true));
    }
}
