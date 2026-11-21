package dev.lsposed.fluidcloud;

import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class) @Config(sdk = 36)
public class NativeNotificationRemindersTest {
    @Test public void newGroupMemberRearmsTheSameModelWithoutRearmingContentUpdates() throws Exception {
        var model = new NotificationModel();
        var reminders = adapter();

        assertTrue(reminders.update(model, "first", 100, true, 3));
        assertEquals(11, model.d());
        assertEquals(101L, model.u());

        assertFalse(reminders.update(model, "first", 110, false, null));
        assertEquals(101L, model.u());

        assertTrue(reminders.update(model, "second", 130, true, 4));
        assertEquals(11, model.d());
        assertEquals(130L, model.u());
    }

    @Test public void capsuleOnlySuccessorDoesNotInheritTheGroupedReminder() throws Exception {
        var model = new NotificationModel();
        var original = new JSONObject().put("showHostMap", "test placement");
        model.metadata = original;
        var reminders = adapter();

        assertTrue(reminders.update(model, "first", 100, true, 3));
        assertEquals("test placement", model.metadata.getString("showHostMap"));
        assertEquals(0, original.optInt("remindType"));

        assertTrue(reminders.update(model, "capsule-only", 150, false, null));
        assertSame(original, model.metadata);
        assertEquals(0, model.d());
        assertFalse(reminders.update(model, "capsule-only", 150, false, null));
    }

    @Test public void nativeDurationDefaultsAndRespectsTheConfiguredRange() {
        assertEquals(3000L, NativeNotificationReminders.durationMillis(null));
        assertEquals(1000L, NativeNotificationReminders.durationMillis(0));
        assertEquals(7000L, NativeNotificationReminders.durationMillis(7));
        assertEquals(15000L, NativeNotificationReminders.durationMillis(16));
    }

    @Test public void legacyCapsuleOnlyAndTimedPopupsUseDistinctNativeMetadata() throws Exception {
        var capsule = LegacyNotificationCloud.metadata(false, false);
        var popup = LegacyNotificationCloud.metadata(true, true);
        assertFalse(capsule.getBoolean("isMilestone"));
        assertEquals(0, capsule.getInt("remindType"));
        assertFalse(capsule.getJSONObject("extensibleActionMap").getBoolean("isDismissible"));
        assertFalse(popup.getBoolean("isMilestone"));
        assertEquals(11, popup.getInt("remindType"));
        assertTrue(popup.getJSONObject("extensibleActionMap").getBoolean("isDismissible"));
    }

    private static NativeNotificationReminders adapter() throws Exception {
        return new NativeNotificationReminders(NotificationModel.class,
                Reflect.field(NotificationModel.class, "metadata", JSONObject.class));
    }

    private static final class NotificationModel {
        JSONObject metadata;
        long g = 100;

        int d() { return metadata == null ? 0 : metadata.optInt("remindType"); }
        long u() { return g; }
    }
}
