package dev.lsposed.fluidcloud;

import android.app.Activity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import java.util.Map;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 36)
public class NativeCardSpacingTest {
    @Test public void configIsBoundedIndependentAndSurvivesOrientationAndLaunchCopies() {
        var values = Map.of("enabled", true, CardSpacing.SIDE, 12, CardSpacing.GAP, -99,
                CardSpacing.HORIZONTAL, 17, CardSpacing.VERTICAL, 99);
        var config = CloudConfig.from(values);
        var expected = new CardSpacing(12, -32, 17, 48);
        assertEquals(expected, config.cardSpacing());
        assertEquals(expected, config.forOrientation(true).withCapsuleOnTop(true).cardSpacing());
        assertEquals(CardSpacing.OEM, CloudConfig.from(Map.of(CardSpacing.SIDE, 12)).cardSpacing());
        assertEquals(CardSpacing.OEM, CloudConfig.from(Map.of("enabled", true)).cardSpacing());
        assertEquals(800, CardSpacing.width(800, 900, 0, 144));
        assertEquals(780, CardSpacing.width(800, 900, 10, 144));
        assertEquals(900, CardSpacing.width(800, 900, -100, 144));
        assertEquals(144, CardSpacing.width(170, 200, 40, 144));
    }

    @Test public void rebindKeepsNewNativeInsetsAndZeroRestoresIndividualEdges() {
        var activity = Robolectric.buildActivity(Activity.class).setup().get();
        View view = new View(activity);
        view.setPaddingRelative(8, 12, 9, 10);
        var spacing = new NativeCardSpacing();
        spacing.inset(view, -5, 6, 2);
        spacing.inset(view, -5, 6, 2);
        assertEquals(3, view.getPaddingStart());
        assertEquals(18, view.getPaddingTop());
        view.setPaddingRelative(3, 20, 4, 12);
        spacing.inset(view, -5, 6, 2);
        assertEquals(26, view.getPaddingTop());
        spacing.onViewDetachedFromWindow(view);
        assertEquals(8, view.getPaddingStart());
        assertEquals(20, view.getPaddingTop());
        assertEquals(9, view.getPaddingEnd());
        assertEquals(10, view.getPaddingBottom());
        assertTrue(view.isPaddingRelative());
    }

    @Test public void tighteningRowsNeverOverlapsAndKeepsFirstOrUnboundHeader() {
        assertEquals(7, NativeCardSpacing.rowGap(7, 0, -18));
        assertEquals(7, NativeCardSpacing.rowGap(7, -1, 18));
        assertEquals(0, NativeCardSpacing.rowGap(7, 2, -18));
        assertEquals(25, NativeCardSpacing.rowGap(7, 1, 18));
    }

    @Test public void instantWidthDoesNotAccumulateWhenReboundOrRotated() {
        var activity = Robolectric.buildActivity(Activity.class).setup().get();
        var body = new FrameLayout(activity);
        int stock = body.getResources().getDisplayMetrics().widthPixels;
        body.setLayoutParams(new FrameLayout.LayoutParams(stock, -2));
        var spacing = new NativeCardSpacing();
        spacing.configure(new CardSpacing(8, 0, 0, 0));
        spacing.adjustWidth(body, false);
        int adjusted = body.getLayoutParams().width;
        assertTrue(adjusted < stock);
        spacing.adjustWidth(body, false);
        assertEquals(adjusted, body.getLayoutParams().width);
        body.setLayoutParams(new FrameLayout.LayoutParams(adjusted, -2));
        spacing.adjustWidth(body, false);
        assertTrue(body.getLayoutParams().width < adjusted);
        spacing.configure(CardSpacing.OEM);
        assertEquals(adjusted, body.getLayoutParams().width);
    }

    @Test public void changingSectionVisibilityMovesOnlyTheTwoOuterEdges() {
        var activity = Robolectric.buildActivity(Activity.class).setup().get();
        var rows = new LinearLayout(activity);
        FrameLayout a = new FrameLayout(activity), b = new FrameLayout(activity);
        a.addView(new View(activity));
        b.addView(new View(activity));
        rows.addView(a);
        rows.addView(b);
        var spacing = new NativeCardSpacing();
        spacing.sectionRows(rows, 5, 8);
        assertEquals(8, a.getPaddingTop());
        assertEquals(0, a.getPaddingBottom());
        assertEquals(0, b.getPaddingTop());
        assertEquals(8, b.getPaddingBottom());
        a.setVisibility(View.GONE);
        spacing.sectionRows(rows, 5, 8);
        assertEquals(8, b.getPaddingTop());
        a.setVisibility(View.VISIBLE);
        spacing.sectionRows(rows, 5, 8);
        assertEquals(0, b.getPaddingTop());
        assertEquals(5, a.getPaddingLeft());
    }

    @Test public void sdkAnchorChangesMeasuredBodyWithoutMovingCameraOrPaintingInsideTheInsets() {
        var activity = Robolectric.buildActivity(Activity.class).setup().get();
        FrameLayout root = new FrameLayout(activity);
        root.setPadding(0, 20, 0, 0);
        View glass = new View(activity);
        root.addView(glass, new FrameLayout.LayoutParams(-1, 0));
        LinearLayout content = new LinearLayout(activity);
        content.setTag("expandedView");
        content.addView(new View(activity), new LinearLayout.LayoutParams(-1, 60));
        root.addView(content, new FrameLayout.LayoutParams(-1, -2));
        var spacing = new NativeCardSpacing();
        spacing.configure(new CardSpacing(0, 0, 4, 8));
        spacing.template(root);
        int dy = Math.round(8 * root.getResources().getDisplayMetrics().density);
        root.measure(View.MeasureSpec.makeMeasureSpec(320, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(500, View.MeasureSpec.AT_MOST));
        assertEquals(20 + 60 + 2 * dy, root.getMeasuredHeight());
        assertEquals(20, root.getPaddingTop());
        assertEquals(0, glass.getPaddingTop());
        spacing.configure(CardSpacing.OEM);
        assertEquals(0, content.getPaddingBottom());
        assertEquals(20, root.getPaddingTop());
    }
}
