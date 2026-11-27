package dev.lsposed.fluidcloud;

import java.util.Map;
import org.junit.Test;
import static org.junit.Assert.*;

public class CapsuleMarqueeOptionsTest {
    @Test public void optionsAreOptInAndSurviveConfigCopies() {
        assertEquals(CapsuleMarqueeOptions.DEFAULTS, CapsuleMarqueeOptions.from(Map.of()));
        var values = Map.of(CapsuleMarqueeOptions.ENABLED, true, CapsuleMarqueeOptions.SPEED, 200,
                CapsuleMarqueeOptions.PAUSE, -1, CapsuleMarqueeOptions.REPEATS, 0);
        assertFalse(CloudConfig.from(values).capsuleMarquee().enabled());
        var config = CloudConfig.from(Map.of("enabled", true, CapsuleMarqueeOptions.ENABLED, true,
                CapsuleMarqueeOptions.SPEED, 200, CapsuleMarqueeOptions.PAUSE, -1, CapsuleMarqueeOptions.REPEATS, 0));
        var options = new CapsuleMarqueeOptions(true, 100, 0, 0);
        assertEquals(options, config.capsuleMarquee());
        assertEquals(options, config.forOrientation(true).withCapsuleOnTop(true).capsuleMarquee());
    }

    @Test public void scrollsAtConfiguredSpeedAndStopsAfterRepeats() {
        var options = new CapsuleMarqueeOptions(true, 50, 500, 2);
        assertEquals(new CapsuleMarqueeOptions.Frame(0, 500), options.frame(100, 180, 2, 0));
        assertEquals(new CapsuleMarqueeOptions.Frame(50, 16), options.frame(100, 180, 2, 1_000));
        assertEquals(new CapsuleMarqueeOptions.Frame(100, 500), options.frame(100, 180, 2, 1_500));
        assertEquals(new CapsuleMarqueeOptions.Frame(140, 16), options.frame(100, 180, 2, 2_400));
        assertEquals(new CapsuleMarqueeOptions.Frame(0, 500), options.frame(100, 180, 2, 2_800));
        assertEquals(new CapsuleMarqueeOptions.Frame(0, -1), options.frame(100, 180, 2, 5_600));
        assertEquals(new CapsuleMarqueeOptions.Frame(0, -1), options.frame(0, 80, 2, 0));
    }

    @Test public void unlimitedModeDoesNotStopAtARoundBoundary() {
        var options = new CapsuleMarqueeOptions(true, 20, 0, 0);
        assertEquals(new CapsuleMarqueeOptions.Frame(20, 16), options.frame(40, 60, 1, 22_000));
        assertEquals(new CapsuleMarqueeOptions.Frame(50, 16), options.frame(40, 60, 1, 23_500));
        assertEquals(0.02f, 60 - options.frame(40, 60, 1, 23_999).offset(), 0.001f);
        assertEquals(new CapsuleMarqueeOptions.Frame(0, 16), options.frame(40, 60, 1, 24_000));
    }
}
