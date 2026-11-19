package dev.lsposed.fluidcloud;

import android.app.Notification;
import android.service.notification.StatusBarNotification;
import dev.howard.colorosenhance.notifications.NotificationCloudStore;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.RuntimeEnvironment;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class) @Config(sdk = 36)
public class NotificationCloudGroupingTest {
    private NotificationCloudStore.Entry<StatusBarNotification> item(String pkg, int id, String conversation) {
        var n = new Notification.Builder(RuntimeEnvironment.getApplication(), "test").setSmallIcon(android.R.drawable.stat_notify_more)
                .setContentTitle("Test").setShortcutId(conversation).build();
        var sbn = new StatusBarNotification(pkg, pkg, id, null, 12345, 0, 0, n, android.os.Process.myUserHandle(), id);
        return new NotificationCloudStore.Entry<>(sbn.getKey(), pkg, "test", sbn, id + 1, false);
    }
    @Test public void groupsAreSeparateForAppsAndConversationsAndPreserveRepresentative() {
        var a = item("first.app", 1, "one"); var b = item("first.app", 2, "one");
        var c = item("first.app", 3, "two"); var d = item("second.app", 4, "one");
        var groups = NotificationCloudGrouping.groups(List.of(a, b, c, d), true);
        assertEquals(3, groups.size()); assertEquals(List.of(a, b), groups.get(0)); assertSame(a, groups.get(0).get(0));
    }
    @Test public void individualModeDoesNotDropNotificationsPastTheNativeFourCandidateLimit() {
        var items = new ArrayList<NotificationCloudStore.Entry<StatusBarNotification>>();
        for (int i = 0; i < 200; i++) items.add(item("test.app", i, null));
        assertEquals(200, NotificationCloudGrouping.groups(items, false).size());
        var grouped = NotificationCloudGrouping.groups(items, true);
        assertEquals(1, grouped.size()); assertEquals(200, grouped.get(0).size());
    }

    @Test public void onlyTheRenderedMembersAndTotalCountInvalidateTheGroupedTemplate() {
        var first = item("test.app", 1, "group");
        var middle = item("test.app", 2, "group");
        var latest = item("test.app", 3, "group");
        var initial = NotificationCloudGrouping.renderVersion(List.of(first, middle, latest));
        assertEquals(initial, NotificationCloudGrouping.renderVersion(List.of(first, updated(middle), latest)));
        assertNotEquals(initial, NotificationCloudGrouping.renderVersion(List.of(updated(first), middle, latest)));
        assertNotEquals(initial, NotificationCloudGrouping.renderVersion(List.of(first, middle, updated(latest))));
        assertNotEquals(initial, NotificationCloudGrouping.renderVersion(List.of(first, latest)));
        assertNotEquals(NotificationCloudGrouping.renderVersion(List.of(latest)),
                NotificationCloudGrouping.renderVersion(List.of(updated(latest))));
    }

    private static NotificationCloudStore.Entry<StatusBarNotification> updated(NotificationCloudStore.Entry<StatusBarNotification> prior) {
        return new NotificationCloudStore.Entry<>(prior.key(), prior.app(), prior.channel(), prior.value(), prior.revision() + 10, prior.ready());
    }
}
