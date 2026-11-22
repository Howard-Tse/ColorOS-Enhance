package dev.lsposed.fluidcloud;

import android.view.View;
import android.widget.FrameLayout;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class) @Config(sdk = 36)
public class NativeCardHeightReflowTest {
    private final FrameLayout list = new FrameLayout(RuntimeEnvironment.getApplication());
    private final NativeCardHeightReflow.Offsets reflow = new NativeCardHeightReflow.Offsets(
            Row.class::isInstance, view -> view instanceof Row row && row.animatedHeight != null,
            view -> ((Row) view).animatedHeight, view -> ((Row) view).contentBottom);

    @Test public void middleCardUsesNativeHeightFramesAndDropsTheLastOffsetOnCompletion() {
        Row earlier = addRow(0, 100);
        Row middle = addRow(110, 160);
        Row later = addRow(280, 100);
        middle.animatedHeight = 100;
        later.setTranslationY(-60); // the native pre-draw moved the follower
        reflow.update(list);
        assertEquals(220f, later.getY(), 0);

        middle.animatedHeight = 145;
        reflow.update(list);
        assertEquals(265f, later.getY(), 0);
        assertEquals(110f, middle.getY(), 0);
        assertEquals(0f, earlier.getY(), 0);

        middle.animatedHeight = null; // the animator ended without drawing its last frame
        reflow.update(list);
        assertEquals(280f, later.getY(), 0);
        assertEquals(10, later.getTop() - middle.getBottom());
    }

    @Test public void rapidChangesUseEachActiveHeightAndPreserveOtherNativeTranslations() {
        Row first = addRow(0, 100);
        Row second = addRow(110, 100);
        Row third = addRow(220, 100);
        first.animatedHeight = 75;
        second.animatedHeight = 112;
        reflow.update(list);
        assertEquals(-25f, second.getTranslationY(), 0);
        assertEquals(-13f, third.getTranslationY(), 0);

        first.animatedHeight = 120;
        second.animatedHeight = 87;
        reflow.update(list);
        assertEquals(20f, second.getTranslationY(), 0);
        assertEquals(7f, third.getTranslationY(), 0);

        first.animatedHeight = null;
        second.animatedHeight = null;
        third.setTranslationY(48); // another OEM scene took ownership
        reflow.update(list);
        assertEquals(0f, second.getTranslationY(), 0);
        assertEquals(48f, third.getTranslationY(), 0);
    }

    @Test public void staleLayoutDetectsOverlappingNativeBoundsRatherThanAnimatedOffsets() {
        Row resized = addRow(0, 160);
        Row follower = addRow(110, 100);
        assertTrue(reflow.staleLayout(list));
        follower.layout(0, 170, 300, 270);
        follower.setTranslationY(-10);
        assertFalse(reflow.staleLayout(list));
        resized.contentBottom = 180;
        assertTrue(reflow.staleLayout(list));
        assertTrue(resized.isLayoutRequested());
        resized.contentBottom = 0;
        resized.measure(View.MeasureSpec.makeMeasureSpec(300, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(180, View.MeasureSpec.EXACTLY));
        assertTrue(reflow.staleLayout(list));
    }

    private Row addRow(int top, int height) {
        Row row = new Row();
        list.addView(row);
        row.measure(View.MeasureSpec.makeMeasureSpec(300, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
        row.layout(0, top, 300, top + height);
        return row;
    }

    private static final class Row extends View {
        Integer animatedHeight;
        int contentBottom;
        Row() { super(RuntimeEnvironment.getApplication()); }
    }
}
