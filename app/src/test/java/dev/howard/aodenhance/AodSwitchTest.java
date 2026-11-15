package dev.howard.aodenhance;

import org.junit.Test;
import java.util.Map;
import static org.junit.Assert.*;

public class AodSwitchTest {
    @Test public void scaleEnabledDoesNotApplySavedFloor() {
        Config c = Config.from(Map.of("enabled", true, "scale_enabled", true,
                "scale", 200, "floor", 20));
        assertArrayEquals(new float[]{3.2f}, AodPolicy.brightness(new float[]{1.6f}, c), .001f);
    }
    @Test public void floorEnabledDoesNotApplySavedScale() {
        Config c = Config.from(Map.of("enabled", true, "floor_enabled", true,
                "scale", 500, "floor", 2));
        assertArrayEquals(new float[]{3.2f, 10}, AodPolicy.brightness(new float[]{1.6f, 10}, c), .001f);
    }
    @Test public void disabledDimRetainsNativeAlphaAndSavedValue() {
        Config c = Config.from(Map.of("enabled", true, "dim", 80));
        assertEquals(.2f, AodPolicy.alpha("background", .2f, c, true), .001f);
        assertEquals(80, c.dim);
    }
    @Test public void allOffReturnsOriginalBrightnessTable() {
        float[] nativeTable = {1.6f, 4};
        assertSame(nativeTable, AodPolicy.brightness(nativeTable,
                Config.from(Map.of("enabled", true, "scale", 500, "floor", 20))));
    }
}
