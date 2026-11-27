package dev.lsposed.fluidcloud;

import android.app.Notification;
import android.app.PendingIntent;
import android.content.Intent;
import android.os.UserHandle;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import dev.howard.colorosenhance.notifications.NotificationCloudStore.Entry;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class) @Config(sdk = 36)
public class NotificationCloudHistoryTest {
    @Test public void sameKeyKeepsDifferentMessageBodiesWithoutDuplicatingAnUnchangedPost() {
        var history = new NotificationCloudHistory();
        history.observe(message(1, "chat", "one", 0), 100);
        history.observe(message(1, "chat", "one", 0), 110);
        history.observe(message(1, "chat", "two", 0), 120);
        var third = message(1, "chat", "three", 0);
        history.observe(third, 130);
        assertEquals(List.of("one", "two"), text(history.forGroup(List.of(entry(third)))));
    }

    @Test public void appReplacementWorksOnBothSidesOfTheRemovalCallback() {
        for (boolean postFirst : new boolean[]{ false, true }) {
            var history = new NotificationCloudHistory();
            var first = message(1, "chat", "old", 0);
            var second = message(2, "chat", "new", 0);
            history.observe(first, 100);
            if (postFirst) history.observe(second, 180);
            history.removed(first, NotificationListenerService.REASON_APP_CANCEL, 200, false, true);
            if (!postFirst) history.observe(second, 220);
            assertEquals(List.of("old"), text(history.forGroup(List.of(entry(second)))));
        }
    }

    @Test public void aReusedNotificationSlotCanShowThePreviousConversation() {
        var history = new NotificationCloudHistory();
        history.observe(message(1, "first chat", "old", 0), 100);
        var current = message(1, "second chat", "new", 0);
        history.observe(current, 200);
        assertEquals(List.of("old"), text(history.forGroup(List.of(entry(current)))));
        assertTrue(history.forGroup(List.of(entry(message(2, "second chat", "another", 0)))).isEmpty());
    }

    @Test public void userRemovalLatePostsOtherConversationsAndClonesDoNotMatchAReplacement() {
        var history = new NotificationCloudHistory();
        var old = message(1, "chat", "old", 0);
        history.observe(old, 100);
        history.removed(old, NotificationListenerService.REASON_CANCEL, 110, false, true);
        var dismissed = message(2, "chat", "next", 0);
        history.observe(dismissed, 120);
        assertTrue(history.forGroup(List.of(entry(dismissed))).isEmpty());
        history.removed(dismissed, NotificationListenerService.REASON_APP_CANCEL_ALL, 130, false, true);
        var clone = message(3, "chat", "clone", 11);
        var other = message(4, "other", "another conversation", 0);
        history.observe(clone, 140);
        history.observe(other, 150);
        var late = message(5, "chat", "late", 0);
        history.observe(late, 6200);
        assertTrue(history.forGroup(List.of(entry(clone), entry(other), entry(late))).isEmpty());
    }

    @Test public void explicitClearDiscardsExistingHistoryEvenWhenLiveTrackingIsPaused() {
        for (int reason : new int[]{ NotificationListenerService.REASON_CANCEL,
                NotificationListenerService.REASON_CANCEL_ALL, NotificationListenerService.REASON_CLICK,
                NotificationListenerService.REASON_LISTENER_CANCEL, NotificationListenerService.REASON_LISTENER_CANCEL_ALL }) {
            var history = new NotificationCloudHistory();
            history.observe(message(1, "chat", "old", 0), 100);
            var current = message(1, "chat", "current", 0);
            history.observe(current, 110);
            assertEquals(List.of("old"), text(history.forGroup(List.of(entry(current)))));

            long previousGeneration = history.generation();
            history.forgetLive();
            history.removed(current, reason, 120, false, false);
            assertTrue(history.generation() > previousGeneration);
            var next = message(2, "chat", "future", 0);
            history.observe(next, 130);
            assertTrue(history.forGroup(List.of(entry(next))).isEmpty());
        }
    }

    @Test public void appCancellationAfterOpenClearsArchivedAndPendingMessagesForThatSource() {
        for (int reason : new int[]{ NotificationListenerService.REASON_APP_CANCEL,
                NotificationListenerService.REASON_APP_CANCEL_ALL }) {
            var history = new NotificationCloudHistory();
            history.observe(message(1, "chat", "old", 0), 100);
            var current = message(1, "chat", "current", 0);
            history.observe(current, 110);
            history.observe(message(1, "chat", "old clone", 11), 120);
            var clone = message(1, "chat", "current clone", 11);
            history.observe(clone, 130);
            history.observe(message(2, "other chat", "old other chat", 0), 140);
            var other = message(2, "other chat", "current other chat", 0);
            history.observe(other, 150);
            var pending = message(3, "chat", "pending old message", 0);
            history.observe(pending, 160);
            history.removed(pending, NotificationListenerService.REASON_APP_CANCEL, 170, false, true);

            history.removed(current, reason, 180, true, true);
            var next = message(1, "chat", "future", 0);
            history.observe(next, 190);
            assertTrue(history.forGroup(List.of(entry(next))).isEmpty());
            assertEquals(List.of("old clone"), text(history.forGroup(List.of(entry(clone)))));
            assertEquals(List.of("old other chat"), text(history.forGroup(List.of(entry(other)))));
            var update = message(1, "chat", "new update", 0);
            history.observe(update, 200);
            assertEquals(List.of("future"), text(history.forGroup(List.of(entry(update)))));
        }
    }

    @Test public void pagesKeepTheRealRowIdentityAndHistoricalActionsAreInert() {
        var source = message(5, "chat", "archived", 0);
        var current = message(5, "chat", "current", 0);
        var target = PendingIntent.getActivity(RuntimeEnvironment.getApplication(), 0,
                new Intent("test.live.open"), PendingIntent.FLAG_IMMUTABLE);
        source.getNotification().contentIntent = target;
        current.getNotification().contentIntent = target;
        var history = new NotificationCloudHistory();
        history.observe(source, 200);
        history.observe(current, 220);
        var live = List.of(entry(current));
        var previous = history.forGroup(live);
        var pages = new NotificationShadePages();
        assertNull(pages.resolve(live, previous, true));
        var newest = pages.resolve(live, previous, false);
        assertFalse(newest.historical());
        assertSame(target, NotificationCloudActions.copyMember(newest.members(), newest).getNotification().contentIntent);
        var archived = newest.step(1);
        assertTrue(archived.historical());
        assertEquals(current.getKey(), archived.rowKey());
        var rendered = NotificationCloudActions.copyMember(archived.members(), archived);
        assertEquals(current.getKey(), rendered.getKey());
        assertEquals("archived", rendered.getNotification().extras.getCharSequence(Notification.EXTRA_TEXT).toString());
        assertNull(rendered.getNotification().contentIntent);
        assertNull(rendered.getNotification().actions);
        assertSame(target, current.getNotification().contentIntent);
    }

    @Test public void progressUpdatesAreIgnoredAndTheArchiveIsBounded() {
        var history = new NotificationCloudHistory();
        for (int i = 0; i < 105; i++) history.observe(message(2, "chat", "msg " + i, 0), i);
        var relevant = List.of(entry(message(2, "chat", "msg 104", 0)));
        var messages = text(history.forGroup(relevant));
        assertEquals(100, messages.size());
        assertEquals("msg 4", messages.getFirst());
        assertEquals("msg 103", messages.getLast());
        history.clearGroup(relevant);
        assertTrue(history.forGroup(relevant).isEmpty());
        var progress = message(1, "other", "45%", 0);
        progress.getNotification().extras.putInt(Notification.EXTRA_PROGRESS_MAX, 100);
        history.observe(progress, 500);
        var update = message(1, "other", "90%", 0);
        update.getNotification().extras.putInt(Notification.EXTRA_PROGRESS_MAX, 100);
        history.observe(update, 600);
        assertTrue(history.forGroup(List.of(entry(update))).isEmpty());
    }

    private static List<String> text(List<Entry<StatusBarNotification>> archive) {
        return archive.stream().map(item -> item.value().getNotification().extras.getString(Notification.EXTRA_TEXT)).toList();
    }

    private static Entry<StatusBarNotification> entry(StatusBarNotification notification) {
        return new Entry<>(notification.getKey(), notification.getPackageName(), "test", notification, 1, true);
    }

    private static StatusBarNotification message(int id, String conversation, String text, int user) {
        Notification notification = new Notification.Builder(RuntimeEnvironment.getApplication(), "test")
                .setSmallIcon(android.R.drawable.stat_notify_more).setShortcutId(conversation)
                .setContentTitle("Messages").setContentText(text).build();
        int uid = 12345 + user * 100_000;
        return new StatusBarNotification("test.app", "test.app", id, null, uid, 0, 0,
                notification, UserHandle.getUserHandleForUid(uid), 1_000L + id);
    }
}
