package dev.lsposed.fluidcloud;

import android.view.View;
import android.widget.FrameLayout;
import java.util.ArrayList;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class) @Config(sdk = 36)
public class NotificationPagerMountTest {
    @Test public void rebuildingOriginalTemplateKeepsPagesAttachedAndRestoresNativeRowOnExit() {
        var ctx = RuntimeEnvironment.getApplication();
        var row = new FrameLayout(ctx);
        var original = new FrameLayout(ctx);
        var pages = new FrameLayout(ctx);
        var translated = new ArrayList<View>();
        row.addView(original, new FrameLayout.LayoutParams(-1, -2));
        original.addView(new View(ctx));
        original.layout(0, 0, 500, 120);
        translated.add(original);
        var mount = new NotificationPagerMount(row, original, pages, translated);
        mount.present(0);
        assertEquals(View.VISIBLE, original.getVisibility());
        assertEquals(View.INVISIBLE, pages.getVisibility());
        mount.present(120);
        original.removeAllViews();
        original.addView(new View(ctx), -1, 280);
        assertSame(row, pages.getParent());
        assertEquals(View.INVISIBLE, original.getVisibility());
        assertEquals(View.VISIBLE, pages.getVisibility());
        for (int height : new int[]{160, 200, 240, 200, 160, 120}) {
            mount.present(height);
            assertEquals(height, pages.getLayoutParams().height);
            assertEquals(height, original.getLayoutParams().height);
        }
        assertTrue(translated.contains(pages));
        mount.remove();
        assertNull(pages.getParent());
        assertFalse(translated.contains(pages));
        assertTrue(translated.contains(original));
        assertEquals(-2, original.getLayoutParams().height);
        assertEquals(View.VISIBLE, original.getVisibility());
    }

    @Test public void lockscreenCommitsOnlyTheSettledSelectedPage() {
        assertTrue(NotificationPagerMount.settled(0, 0, 0));
        assertFalse(NotificationPagerMount.settled(.65f, 0, 1));
        assertFalse(NotificationPagerMount.settled(1, 1, 2));
        assertFalse(NotificationPagerMount.settled(.96f, 1, 0));
        assertFalse(NotificationPagerMount.settled(1, 0, 0));
        assertTrue(NotificationPagerMount.settled(1, 1, 0));
    }

    @Test public void heightFollowsActualPageFractionInBothDirectionsWithoutGuessingMissingSizes() {
        assertEquals(120, NotificationPagerMount.between(120, 0, 0));
        assertEquals(160, NotificationPagerMount.between(120, 280, .25f));
        assertEquals(200, NotificationPagerMount.between(120, 280, .5f));
        assertEquals(240, NotificationPagerMount.between(120, 280, .75f));
        assertEquals(160, NotificationPagerMount.between(280, 120, .75f));
        assertEquals(280, NotificationPagerMount.between(120, 280, 1));
        assertEquals(120, NotificationPagerMount.between(120, 120, .9f));
        assertEquals(0, NotificationPagerMount.between(120, 0, .5f));
        assertEquals(0, NotificationPagerMount.between(0, 280, .5f));
    }

    @Test public void reversingPastTheStartCannotClearInTheOppositeDirection() {
        assertEquals(160f, NativeNotificationCardTouch.outwardDistance(160f, 1), 0f);
        assertEquals(-160f, NativeNotificationCardTouch.outwardDistance(-160f, -1), 0f);
        float reversed = NativeNotificationCardTouch.outwardDistance(-250f, 1);
        assertFalse(NativeNotificationCardTouch.shouldDismiss(reversed, -2500f, 400, 10, 50));
    }

    @Test public void aShortSwipeNeedsAnOutwardFlingAndLongDragsCanClearWithoutOne() {
        assertFalse(NativeNotificationCardTouch.shouldDismiss(24f, 2500f, 400, 10, 50));
        assertFalse(NativeNotificationCardTouch.shouldDismiss(-64f, 500f, 400, 10, 50));
        assertTrue(NativeNotificationCardTouch.shouldDismiss(-64f, -500f, 400, 10, 50));
        assertTrue(NativeNotificationCardTouch.shouldDismiss(140f, 0f, 400, 10, 50));
    }
}
