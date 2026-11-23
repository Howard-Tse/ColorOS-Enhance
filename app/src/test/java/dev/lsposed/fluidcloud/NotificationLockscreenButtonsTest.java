package dev.lsposed.fluidcloud;

import android.app.Notification;
import android.app.PendingIntent;
import android.app.RemoteInput;
import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.Icon;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class) @Config(sdk = 36)
public class NotificationLockscreenButtonsTest {
    private Notification.Action.Builder action(String title, int id) {
        Context context = RuntimeEnvironment.getApplication();
        PendingIntent target = PendingIntent.getBroadcast(context, id, new Intent(title).setPackage(context.getPackageName()),
                PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Action.Builder((Icon) null, title, target);
    }

    @Test public void previewKeepsTheBodyAndButtonTargetsSeparate() {
        var source = new Notification();
        source.actions = new Notification.Action[]{action("更新进度", 1).build(), action("结束测试", 2).build(), action("更多", 3).build()};
        Context context = RuntimeEnvironment.getApplication();
        source.contentIntent = PendingIntent.getActivity(context, 4,
                new Intent(context, dev.howard.colorosenhance.notifications.NotificationTestActivity.class)
                        .putExtra("notification_id", 7290), PendingIntent.FLAG_IMMUTABLE);
        source.fullScreenIntent = source.contentIntent;
        source.deleteIntent = source.actions[1].actionIntent;
        var preview = source.clone();
        NotificationLockscreenCards.preparePreview(preview);
        assertEquals(2, preview.actions.length);
        for (int i = 0; i < 2; i++) {
            assertEquals(source.actions[i].title, preview.actions[i].title);
            assertEquals(source.actions[i].actionIntent, preview.actions[i].actionIntent);
        }
        assertEquals(source.contentIntent, preview.contentIntent);
        assertNull(preview.fullScreenIntent);
        assertNull(preview.deleteIntent);
        assertEquals(3, source.actions.length);
        assertNotNull(source.contentIntent);
    }

    @Test public void missingBodyTargetDoesNotPromoteAButtonOrFullScreenIntent() {
        var preview = new Notification();
        var button = action("更新进度", 1).build();
        preview.actions = new Notification.Action[]{button};
        preview.fullScreenIntent = button.actionIntent;
        NotificationLockscreenCards.preparePreview(preview);
        assertNull(preview.contentIntent);
        assertNull(preview.fullScreenIntent);
        assertEquals(button.actionIntent, preview.actions[0].actionIntent);
    }

    @Test public void authenticatedAndReplyButtonsNeverReachTheLockscreenTemplate() {
        var preview = new Notification();
        var simple = action("更新进度", 4).build();
        preview.actions = new Notification.Action[]{action("授权", 1).setAuthenticationRequired(true).build(),
                action("回复", 2).addRemoteInput(new RemoteInput.Builder("reply").build()).build(),
                action("发送图片", 3).addRemoteInput(new RemoteInput.Builder("image").setAllowFreeFormInput(false)
                        .setAllowDataType("image/png", true).build()).build(), simple};
        NotificationLockscreenCards.preparePreview(preview);
        assertArrayEquals(new Notification.Action[]{simple}, preview.actions);
    }
}
