package com.howard.batterypercentweight.hook;

import android.graphics.Canvas;
import android.graphics.Matrix;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 36, manifest = Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class PercentContentDrawingTest {
    @Test public void offsetsMoveOnlyNativeContentAndDoNotAccumulate() throws Throwable {
        Canvas canvas = new Canvas();
        canvas.translate(8, 12);
        int saves = canvas.getSaveCount();
        Object result = new Object();
        for (int frame = 0; frame < 2; frame++) {
            assertSame(result, PercentContentDrawing.draw(canvas, 3, -6, () -> {
                assertTranslation(canvas, 11, 6);
                return result;
            }));
            assertTranslation(canvas, 8, 12);
            assertEquals(saves, canvas.getSaveCount());
        }
    }

    @Test public void nativeFailureRestoresCanvasAndPropagatesWithoutSecondDraw() throws Throwable {
        Canvas canvas = new Canvas();
        RuntimeException nativeFailure = new RuntimeException("native draw failed");
        int[] calls = {0};
        try {
            PercentContentDrawing.draw(canvas, -3, 6, () -> {
                calls[0]++;
                assertTranslation(canvas, -3, 6);
                throw nativeFailure;
            });
            fail("native failure must propagate");
        } catch (RuntimeException actual) {
            assertSame(nativeFailure, actual);
        }
        assertEquals(1, calls[0]);
        assertTranslation(canvas, 0, 0);
        assertEquals(1, canvas.getSaveCount());
    }

    @Test public void disabledOffsetsLeaveNativeCanvasStateUntouched() throws Throwable {
        Canvas canvas = new Canvas();
        PercentContentDrawing.draw(canvas, 0, 0, () -> {
            assertEquals(1, canvas.getSaveCount());
            assertTranslation(canvas, 0, 0);
            return null;
        });
    }

    private static void assertTranslation(Canvas canvas, float x, float y) {
        float[] values = new float[9];
        canvas.getMatrix().getValues(values);
        assertEquals(x, values[Matrix.MTRANS_X], 0.0001f);
        assertEquals(y, values[Matrix.MTRANS_Y], 0.0001f);
    }
}
