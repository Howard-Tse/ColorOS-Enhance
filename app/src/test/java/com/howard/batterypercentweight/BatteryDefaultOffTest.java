package com.howard.batterypercentweight;
import org.junit.Test;
import static org.junit.Assert.*;
public class BatteryDefaultOffTest {
    @Test public void freshInstallDoesNotChangeBatteryTypography() {
        TypographySettings settings = TypographySettings.defaults();
        assertFalse(settings.enabled());
        assertFalse(settings.appliesWeight());
        assertEquals(570, settings.weight());
    }
}
