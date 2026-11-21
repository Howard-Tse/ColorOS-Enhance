package dev.lsposed.fluidcloud;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.AnimatorSet;
import android.animation.ValueAnimator;
import android.app.Notification;
import android.app.PendingIntent;
import android.content.Intent;
import android.os.Bundle;
import android.os.Looper;
import android.widget.TextView;
import dev.howard.colorosenhance.notifications.NotificationCloudRules;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class) @Config(sdk = 36)
public class NotificationRenderStyleTest {
    @Test public void clearBothKeepsTheCardIfTheOriginalCannotBeCleared() {
        var operations = new java.util.ArrayList<String>();
        NotificationOpenCleanup.applyAfterOpen(NotificationCloudRules.AfterOpen.BOTH,
                () -> operations.add("attempted original dismissal"), () -> operations.add("returned original"));
        assertEquals(java.util.List.of("attempted original dismissal"), operations);
        operations.clear();
        NotificationOpenCleanup.applyAfterOpen(NotificationCloudRules.AfterOpen.CARD,
                () -> operations.add("attempted original dismissal"), () -> operations.add("returned original"));
        assertEquals(java.util.List.of("returned original"), operations);
    }

    @Test public void cappedCapsuleCannotAnimateThroughAnOversizedWidth() throws Exception {
        var stationary = NativeNotificationCapsule.boundedAnimation(
                "[{\"startValue\":226,\"endValue\":240,\"duration\":200}]", 140);
        assertTrue(stationary.stationary());
        var animator = NativeNotificationCapsule.boundedAnimation(
                "[{\"startValue\":110,\"endValue\":228,\"duration\":200}]", 140);
        assertFalse(animator.stationary());
        var segment = new org.json.JSONArray(animator.segments()).getJSONObject(0);
        assertEquals(110, segment.getInt("startValue"));
        assertEquals(140, segment.getInt("endValue"));
        assertEquals(200, segment.getInt("duration"));
        assertNull(NativeNotificationCapsule.boundedAnimation("[{\"startValue\":110,\"endValue\":130}]", 140));
    }

    @Test public void sameCappedWidthStillCommitsEachNewCaptionWithoutAWidthTransition() {
        var caption = new TextView(RuntimeEnvironment.getApplication());
        caption.setText("↓ 1 KB/s");
        int tag = dev.howard.colorosenhance.R.drawable.ic_notification_cloud;
        var params = new Bundle();
        params.putInt("type", 0);
        params.putInt("animObjTag", tag);
        params.putString("animValue", "[{\"startValue\":226,\"endValue\":240,\"duration\":200}]");
        for (String next : java.util.List.of("↓ 18 KB/s", "↓ 43 KB/s")) {
            var content = ValueAnimator.ofFloat(0f, 1f);
            content.setDuration(200L);
            var update = new AnimatorSet();
            update.playTogether(content);
            update.addListener(new AnimatorListenerAdapter() {
                @Override public void onAnimationEnd(Animator animation) { caption.setText(next); }
            });
            caption.setTag(tag, update);
            assertNull(NativeNotificationCapsule.constrainWidthAnimation(params, 140, caption));
            assertEquals(0L, update.getDuration());
            org.robolectric.Shadows.shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(32));
            assertEquals(next, caption.getText().toString());
        }
    }

    @Test public void sameCappedWidthWithoutATaggedAnimatorStillReachesTheNativeCallback() throws Exception {
        var params = new Bundle();
        params.putInt("type", 0);
        params.putString("animValue", "[{\"startValue\":226,\"endValue\":240,\"duration\":200}]");
        Bundle capped = NativeNotificationCapsule.constrainWidthAnimation(params, 140, null);
        assertNotSame(params, capped);
        var segment = new org.json.JSONArray(capped.getString("animValue")).getJSONObject(0);
        assertEquals(140, segment.getInt("startValue"));
        assertEquals(140, segment.getInt("endValue"));
        assertSame(params, NativeNotificationCapsule.constrainWidthAnimation(params, Integer.MAX_VALUE, null));
    }

    @Test public void capsuleCaptionOverrideUsesTheRenderCopyAndRestoresItsOriginalExtras() throws Throwable {
        var context = RuntimeEnvironment.getApplication();
        var original = new Notification.Builder(context, "test")
                .setContentText("Full notification body").setShortCriticalText("Original").build();
        var render = original.clone();
        var extras = render.extras;
        String parsed = NativeNotificationCapsule.readWithCaption(context, render, "12 KB/s", input -> {
            assertSame(render, input);
            assertEquals("Full notification body", input.extras.getCharSequence(Notification.EXTRA_TEXT));
            assertEquals("Original", original.getShortCriticalText());
            return input.getShortCriticalText();
        });
        assertEquals("12 KB/s", parsed);
        assertSame(extras, render.extras);
        assertEquals("Original", render.getShortCriticalText());
        assertThrows(IllegalStateException.class, () -> NativeNotificationCapsule.readWithCaption(context, render, "50%",
                input -> { throw new IllegalStateException("Parser rejected template"); }));
        assertSame(extras, render.extras);
        assertEquals("Original", render.getShortCriticalText());
    }

    @Test public void longCaptionStillUsesThePlatformSanitizer() throws Throwable {
        var context = RuntimeEnvironment.getApplication();
        var notification = new Notification.Builder(context, "test").setShortCriticalText("Original").build();
        String longCaption = "X".repeat(4096);
        String expected = new Notification.Builder(context, "test").setShortCriticalText(longCaption).build().getShortCriticalText();
        String actual = NativeNotificationCapsule.readWithCaption(context, notification, longCaption, input -> {
            assertNotSame(notification, input);
            return input.getShortCriticalText();
        });
        assertEquals(expected, actual);
        assertEquals("Original", notification.getShortCriticalText());
    }
    @Test public void moreBroadcastMatchesItsDataAndRejectsOtherSchemes() {
        var filter = NotificationCloudActions.menuFilter("test.more");
        assertTrue(filter.matchData(null, "coloros-enhance", android.net.Uri.parse("coloros-enhance://notification/test")) >= 0);
        assertTrue(filter.matchData(null, "https", android.net.Uri.parse("https://notification/test")) < 0);
    }
    @Test public void stylingProgressKeepsActionIntentsAndDoesNotMutateOriginal() {
        var context = RuntimeEnvironment.getApplication();
        var action = PendingIntent.getBroadcast(context, 1, new Intent("test.action"), PendingIntent.FLAG_IMMUTABLE);
        var original = new Notification.Builder(context, "test").setSmallIcon(android.R.drawable.stat_notify_more)
                .setContentTitle("Progress").setProgress(100, 35, false)
                .addAction(new Notification.Action.Builder(null, "Update", action).build()).build();
        for (var style : NotificationCloudRules.Style.values()) {
            var copy = original.clone();
            NotificationCloudActions.applyStyle(context, copy, style);
            assertNotNull(copy.actions); assertEquals(1, copy.actions.length);
            assertNotNull(copy.actions[0]); assertEquals(action, copy.actions[0].actionIntent);
            assertEquals(35, copy.extras.getInt(Notification.EXTRA_PROGRESS));
            assertEquals("Update", original.actions[0].title);
            assertNull(original.extras.getString(Notification.EXTRA_TEMPLATE));
        }
    }
}
