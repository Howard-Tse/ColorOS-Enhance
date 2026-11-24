package dev.lsposed.fluidcloud.pager;

import android.app.Activity;
import android.os.Looper;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.widget.FrameLayout;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.robolectric.Shadows.shadowOf;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class) @Config(sdk = 36)
public class GroupPageAdapterTest {
    @Test public void viewportDoesNotLimitMeasurementOfTallerNeighbor() {
        var context = RuntimeEnvironment.getApplication();
        var frame = new GroupPageAdapter.PageFrame(context, null);
        var template = new FrameLayout(context);
        var content = new View(context);
        content.setMinimumHeight(240);
        template.addView(content, -1, -2);
        frame.addView(template, -1, -2);
        frame.measure(View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(100, View.MeasureSpec.EXACTLY));
        frame.layout(0, 0, 400, 100);
        assertEquals(100, frame.getHeight());
        assertEquals(240, template.getHeight());
        assertEquals(400, template.getWidth());
        assertEquals(100f, template.getHeight() * template.getScaleY(), .01f);
        assertEquals(template.getScaleX(), template.getScaleY(), .001f);
        assertEquals(0f, template.getPivotY(), .001f);
        frame.measure(View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(240, View.MeasureSpec.EXACTLY));
        frame.layout(0, 0, 400, 240);
        assertEquals(1f, template.getScaleY(), .001f);
        assertEquals(1f, template.getScaleX(), .001f);
    }

    @Test public void stationaryHoldReachesSelectedMemberEvenOverAnActionButton() {
        var presses = new ArrayList<String>();
        int[] clicks = {0};
        try (var activity = Robolectric.buildActivity(Activity.class).setup()) {
            var frame = new GroupPageAdapter.PageFrame(activity.get(), presses::add);
            View button = new View(activity.get());
            button.setOnClickListener(v -> clicks[0]++);
            frame.addView(button, -1, -1);
            activity.get().setContentView(frame);
            shadowOf(Looper.getMainLooper()).idle();
            frame.layout(0, 0, 300, 200);
            button.layout(0, 0, 300, 200);
            frame.bindMember("second");
            long down = SystemClock.uptimeMillis();
            touch(frame, down, MotionEvent.ACTION_DOWN, 20);
            touch(frame, down, MotionEvent.ACTION_UP, 20);
            shadowOf(Looper.getMainLooper()).idle();
            assertEquals(1, clicks[0]);
            down = SystemClock.uptimeMillis();
            touch(frame, down, MotionEvent.ACTION_DOWN, 20);
            frame.bindMember("second");
            waitForHold();
            assertEquals(List.of("second"), presses);
            touch(frame, down, MotionEvent.ACTION_CANCEL, 20); // ColorOS cancels the stream when it opens the menu.
            assertEquals(1, clicks[0]);
        }
    }

    @Test public void draggingCancellationRebindingAndDetachingDiscardPendingHolds() {
        var presses = new ArrayList<String>();
        try (var activity = Robolectric.buildActivity(Activity.class).setup()) {
            var frame = new GroupPageAdapter.PageFrame(activity.get(), presses::add);
            frame.setClickable(true);
            activity.get().setContentView(frame);
            shadowOf(Looper.getMainLooper()).idle();
            frame.layout(0, 0, 300, 200);
            frame.bindMember("first");
            long down = SystemClock.uptimeMillis();
            touch(frame, down, MotionEvent.ACTION_DOWN, 20);
            touch(frame, down, MotionEvent.ACTION_MOVE, 200);
            waitForHold();
            touch(frame, down, MotionEvent.ACTION_UP, 200);
            down = SystemClock.uptimeMillis();
            touch(frame, down, MotionEvent.ACTION_DOWN, 20);
            touch(frame, down, MotionEvent.ACTION_CANCEL, 20);
            waitForHold();
            down = SystemClock.uptimeMillis();
            touch(frame, down, MotionEvent.ACTION_DOWN, 20);
            frame.bindMember(null);
            frame.bindMember("second");
            waitForHold();
            touch(frame, down, MotionEvent.ACTION_CANCEL, 20);
            down = SystemClock.uptimeMillis();
            touch(frame, down, MotionEvent.ACTION_DOWN, 20);
            activity.get().setContentView(new View(activity.get()));
            waitForHold();
            assertTrue(presses.isEmpty());
        }
    }

    private static void touch(View view, long down, int action, float x) {
        MotionEvent event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, 35, 0);
        try { view.dispatchTouchEvent(event); }
        finally { event.recycle(); }
    }

    private static void waitForHold() {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ViewConfiguration.getLongPressTimeout() + 60));
    }
}
