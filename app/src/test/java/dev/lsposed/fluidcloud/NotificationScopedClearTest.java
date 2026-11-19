package dev.lsposed.fluidcloud;

import android.app.Notification;
import android.service.notification.StatusBarNotification;
import java.util.List;
import java.util.Set;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.assertEquals;

@RunWith(RobolectricTestRunner.class) @Config(sdk = 36)
public class NotificationScopedClearTest {
    @Test public void eachPageClearsOnlyItsOwnNotificationsEvenWithinTheSameAppAndGroup() {
        var main = item(1, "mixed", false, false);
        var collected = item(2, "mixed", false, false);
        var mixedSummary = item(3, "mixed", true, false);
        var collectedChild = item(4, "collected", false, false);
        var collectedSummary = item(5, "collected", true, false);
        var ongoing = item(6, null, false, true);
        var all = List.of(main, collected, mixedSummary, collectedChild, collectedSummary, ongoing);
        var partition = Set.of(collected.getKey(), collectedChild.getKey(), ongoing.getKey());

        assertEquals(Set.of(main.getKey()), NotificationScopedClear.allowedKeys(all, partition, false));
        assertEquals(Set.of(collected.getKey(), collectedChild.getKey(), collectedSummary.getKey()),
                NotificationScopedClear.allowedKeys(all, partition, true));
    }

    @Test public void groupSummaryCannotIndirectlyClearAnOngoingChild() {
        var clearable = item(1, "ongoing", false, false);
        var ongoing = item(2, "ongoing", false, true);
        var summary = item(3, "ongoing", true, false);
        var all = List.of(clearable, ongoing, summary);
        var partition = Set.of(clearable.getKey(), ongoing.getKey());

        assertEquals(Set.of(clearable.getKey()), NotificationScopedClear.allowedKeys(all, partition, true));
    }

    private static StatusBarNotification item(int id, String group, boolean summary, boolean ongoing) {
        var notification = new Notification.Builder(RuntimeEnvironment.getApplication(), "test")
                .setSmallIcon(android.R.drawable.stat_notify_more).setGroup(group).setGroupSummary(summary)
                .setOngoing(ongoing).build();
        return new StatusBarNotification("test.app", "test.app", id, null, 12345, 0, 0,
                notification, android.os.Process.myUserHandle(), id);
    }
}
