package dev.howard.colorosenhance.notifications;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.NotificationChannel;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class) @Config(sdk = 36)
public class NotificationCollectionSummaryTest {
    @Test public void cleaningUpOldSummaryRetainsOtherNotifications() {
        var context = RuntimeEnvironment.getApplication(); var manager = context.getSystemService(NotificationManager.class);
        manager.createNotificationChannel(new NotificationChannel("test", "Test", NotificationManager.IMPORTANCE_DEFAULT));
        var notification = new Notification.Builder(context, "test").setSmallIcon(android.R.drawable.stat_notify_more).build();
        manager.notify(100, notification);
        manager.notify(NotificationMetadataProvider.LEGACY_COLLECTION_TAG, NotificationMetadataProvider.LEGACY_COLLECTION_ID, notification);
        assertEquals(2, manager.getActiveNotifications().length);
        NotificationMetadataProvider.clearLegacyCollection(context);
        assertEquals(1, manager.getActiveNotifications().length);
        assertEquals(100, manager.getActiveNotifications()[0].getId());
    }
}
