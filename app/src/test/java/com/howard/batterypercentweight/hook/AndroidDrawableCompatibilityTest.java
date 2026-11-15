package com.howard.batterypercentweight.hook;

import android.content.Context;
import android.graphics.Paint;
import android.graphics.Typeface;
import com.howard.batterypercentweight.TypographySettings;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 36, manifest = Config.NONE, qualifiers = "xxhdpi")
public class AndroidDrawableCompatibilityTest {
    // Preserve the relevant native contract: level updates skip measurement while hidden.
    public static class ModernHorizontal {
        private final Paint percentInPaint = new Paint();
        boolean isShowPercentIn;
        float measuredSize;

        ModernHorizontal() {
            percentInPaint.setTypeface(Typeface.SANS_SERIF);
            percentInPaint.setTextSize(20.0f);
        }

        public Context getContext() { return RuntimeEnvironment.getApplication(); }
        public void updatePercentInTextBounds() { measuredSize = percentInPaint.getTextSize(); }
        public void updateBatteryLevel() {
            if (isShowPercentIn) updatePercentInTextBounds();
        }
        public void setFontTypeface(Typeface typeface, int weight) {
            percentInPaint.setTypeface(typeface);
            if (isShowPercentIn) updatePercentInTextBounds();
        }
    }

    @Test public void sizeChangeRecomputesHiddenPercentBeforeNextDrawAndRestoresOnDisable() {
        ModernHorizontal drawable = new ModernHorizontal();
        DrawableController controller = controller(settings(true, false, true));
        controller.onConstructed(drawable,
                PaintFieldResolver.resolve(ModernHorizontal.class).orElseThrow(),
                MethodResolver.findBoundsUpdater(ModernHorizontal.class).orElseThrow(),
                MethodResolver.findContextGetter(ModernHorizontal.class).orElseThrow());

        assertEquals(30.0f, drawable.percentInPaint.getTextSize(), 0.001f);
        assertEquals(30.0f, drawable.measuredSize, 0.001f);
        controller.refresh(settings(false, false, true));
        assertEquals(20.0f, drawable.percentInPaint.getTextSize(), 0.001f);
        assertEquals(20.0f, drawable.measuredSize, 0.001f);
    }

    @Test public void newSystemTypefaceBecomesRestoreBaselineWhileCustomSizeSurvives()
            throws Exception {
        ModernHorizontal drawable = new ModernHorizontal();
        DrawableController controller = controller(settings(true, true, true));
        var paint = PaintFieldResolver.resolve(ModernHorizontal.class).orElseThrow();
        controller.onConstructed(drawable, paint,
                MethodResolver.findBoundsUpdater(ModernHorizontal.class).orElseThrow(),
                MethodResolver.findContextGetter(ModernHorizontal.class).orElseThrow());
        var callback = MethodResolver.findTypefaceSetter(ModernHorizontal.class);
        org.junit.Assert.assertTrue("new native callback must be found", callback.isPresent());
        callback.orElseThrow().invoke(drawable, Typeface.MONOSPACE, 650);
        controller.afterSystemFontChanged(drawable, paint, null);

        assertEquals(30.0f, drawable.percentInPaint.getTextSize(), 0.001f);
        controller.refresh(settings(false, true, true));
        assertSame(Typeface.MONOSPACE, drawable.percentInPaint.getTypeface());
        assertEquals(20.0f, drawable.percentInPaint.getTextSize(), 0.001f);
    }

    private static DrawableController controller(TypographySettings settings) {
        ErrorLogger errors = (message, error) -> { throw new AssertionError(message, error); };
        return new DrawableController(new DrawableRegistry(), new AndroidDrawableMutator(errors),
                Runnable::run, settings, errors);
    }

    private static TypographySettings settings(boolean enabled, boolean weight, boolean size) {
        return new TypographySettings(enabled, weight, 700, size, 100,
                false, 0, false, 0);
    }
}
