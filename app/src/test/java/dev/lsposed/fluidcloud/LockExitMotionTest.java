package dev.lsposed.fluidcloud;

import android.graphics.RectF;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 36, manifest = Config.NONE)
public class LockExitMotionTest {
    @Test public void bodyKeepsItsHeightBeforeTheCameraDotShrinksAndFades() {
        var exit = LockExitMotion.from(new RectF(90f, 45f, 210f, 75f), 135f, 24f);
        assertEquals(exit.start(), exit.frame(0f));
        var body = exit.frame(.5f);
        assertEquals(30f, body.bottom() - body.top(), .001f);
        assertTrue(body.right() - body.left() < 120f);
        var dot = exit.frame(.67f);
        assertEquals(30f, dot.right() - dot.left(), .001f);
        assertEquals(135f, (dot.left() + dot.right()) / 2f, .001f);
        assertEquals(dot.radiusX(), dot.radiusY(), .001f);
        assertEquals(255f, exit.frame(.82f).alpha(), 0f);
        var fading = exit.frame(.93f);
        assertTrue(fading.bottom() - fading.top() < 30f);
        assertTrue(fading.alpha() > 0f && fading.alpha() < dot.alpha());
        assertEquals(exit.end(), exit.frame(1f));
    }
}
