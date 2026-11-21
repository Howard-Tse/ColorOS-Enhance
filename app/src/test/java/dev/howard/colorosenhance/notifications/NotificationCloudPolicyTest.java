package dev.howard.colorosenhance.notifications;

import android.app.Notification;
import android.app.NotificationManager;
import android.service.notification.StatusBarNotification;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class) @Config(sdk = 36)
public class NotificationCloudPolicyTest {
    private StatusBarNotification notification(String pkg, String category) {
        var n = new Notification.Builder(RuntimeEnvironment.getApplication(), "test").setSmallIcon(android.R.drawable.stat_notify_more)
                .setContentTitle("Test").setCategory(category).build();
        return new StatusBarNotification(pkg, pkg, 1, null, 12345, 0, 0, n, android.os.Process.myUserHandle(), 1);
    }
    private NotificationCloudRules rules() throws Exception { return NotificationCloudRules.DEFAULT.with("allowed", new org.json.JSONArray().put("test.app")); }
    private NotificationCloudRules.Mode evaluate(StatusBarNotification sbn, int importance, int channel, boolean suspended) throws Exception {
        return NotificationCloudPolicy.evaluate(sbn, android.os.Process.myUserHandle(), importance, channel, suspended, rules());
    }
    @Test public void silentOngoingUsesSilentPolicyAndRingerModeIsNotPartOfClassification() throws Exception {
        var sbn = notification("test.app", Notification.CATEGORY_SERVICE); sbn.getNotification().flags |= Notification.FLAG_ONGOING_EVENT;
        assertEquals(NotificationCloudRules.Mode.NATIVE, evaluate(sbn, 2, 2, false));
        assertEquals(NotificationCloudRules.Mode.CAPSULE, evaluate(sbn, 3, 3, false));
        assertEquals(NotificationCloudRules.Mode.NATIVE, evaluate(sbn, 3, 2, false));
    }
    @Test public void nativeMediaCriticalAndSuspendedRemainUntouchedEvenWithAnAppOverride() throws Exception {
        for (String category : new String[]{Notification.CATEGORY_ALARM, Notification.CATEGORY_SYSTEM, Notification.CATEGORY_TRANSPORT})
            assertEquals(NotificationCloudRules.Mode.NATIVE, evaluate(notification("test.app", category), 4, 4, false));
        assertEquals(NotificationCloudRules.Mode.NATIVE, evaluate(notification("test.app", Notification.CATEGORY_MESSAGE), 4, 4, true));
        var sbn = notification("test.app", Notification.CATEGORY_MESSAGE); sbn.getNotification().extras.putString("oplus.livealert.card", "native");
        assertEquals(NotificationCloudRules.Mode.NATIVE, evaluate(sbn, 4, 4, false));
        sbn.getNotification().extras.remove("oplus.livealert.card"); sbn.getNotification().extras.putString(Notification.EXTRA_TEMPLATE, "android.app.Notification$MediaStyle");
        assertEquals(NotificationCloudRules.Mode.NATIVE, evaluate(sbn, 4, 4, false));
    }
    @Test public void ordinaryConvertsButBlockedAndUnlistedNeverDo() throws Exception {
        assertEquals(NotificationCloudRules.Mode.CAPSULE, evaluate(notification("test.app", Notification.CATEGORY_MESSAGE), 3, 3, false));
        assertEquals(NotificationCloudRules.Mode.NATIVE, evaluate(notification("test.app", Notification.CATEGORY_MESSAGE), 0, 0, false));
        assertEquals(NotificationCloudRules.Mode.NATIVE, evaluate(notification("other.app", Notification.CATEGORY_MESSAGE), 3, 3, false));
    }
    @Test public void existingLegacyLiveAlertsStayInTheOriginalPipeline() throws Exception {
        var sbn = notification("test.app", Notification.CATEGORY_MESSAGE);
        sbn.getNotification().extras.putString("oplusLiveAlertOptions", "{\"remindType\":11}");
        assertEquals(NotificationCloudRules.Mode.NATIVE, evaluate(sbn, 4, 4, false));
    }
}
