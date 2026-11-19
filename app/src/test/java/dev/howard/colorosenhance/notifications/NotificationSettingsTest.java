package dev.howard.colorosenhance.notifications;

import android.os.Bundle;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class) @Config(sdk = 36)
public class NotificationSettingsTest {
    @Test public void nativeAndComposeOptionsRoundTripAllChoicesWithoutResettingOtherRules() {
        NotificationCloudRules value = NotificationCloudRules.DEFAULT;
        for (var option : NotificationSettingsOptions.ALL) {
            for (int index = 0; index < (option.toggle() ? 2 : option.values().size()); index++) {
                value = option.change(value, index);
                assertEquals(index, option.selection(NotificationCloudRules.parse(value.encode())));
            }
        }
        assertTrue(dev.howard.colorosenhance.migration.MigrationProtocol.knownKey("cloud", NotificationCloudRules.KEY));
    }
    @Test public void switchingListModesPreservesBothLists() throws Exception {
        var allow = new org.json.JSONArray().put("allowed.app"); var deny = new org.json.JSONArray().put("denied.app");
        var value = NotificationCloudRules.DEFAULT.with("allowed", allow).with("denied", deny);
        assertTrue(value.accepts("allowed.app")); assertFalse(value.accepts("other.app"));
        value = value.with("denylist", true); assertTrue(value.accepts("other.app")); assertFalse(value.accepts("denied.app"));
        value = value.with("denylist", false); assertEquals(java.util.Set.of("allowed.app"), value.allowed()); assertEquals(java.util.Set.of("denied.app"), value.denied());
    }
    @Test public void metadataProviderRejectsAppForgedPublishAndDoesNotPersistNotificationContent() {
        var provider = Robolectric.buildContentProvider(NotificationMetadataProvider.class).create().get();
        Bundle args = new Bundle(); args.putString("package", "test.app"); args.putString("token", "forged");
        assertThrows(SecurityException.class, () -> provider.call("publish", null, args));
        assertThrows(SecurityException.class, () -> provider.call("clear_legacy_collection", null, args));
        assertThrows(SecurityException.class, () -> provider.call("publish_alert_renderer", null, args));
        assertTrue(provider.call("read", null, args).isEmpty());
    }
    @Test public void appEditorChangesOnlySelectedApplicationAndSharesLists() {
        var value = NotificationCloudRules.DEFAULT.with("floating", true)
                .with("allowed", new org.json.JSONArray().put("other.app"));
        value = value.editApp("test.app", true, true, NotificationCloudRules.Floating.OFF,
                java.util.Map.of("test.app/news", NotificationCloudRules.Mode.EXPAND));
        var saved = NotificationCloudRules.parse(value.encode());
        assertEquals(java.util.Set.of("test.app", "other.app"), saved.allowed());
        assertEquals(java.util.Set.of("test.app"), saved.denied());
        assertFalse(saved.floating("test.app"));
        assertTrue(saved.floating("other.app"));
        assertEquals(NotificationCloudRules.Mode.EXPAND, saved.mode("test.app", "news", NotificationCloudRules.Kind.ORDINARY));
        assertEquals(NotificationCloudRules.Mode.NATIVE,
                saved.with("denylist", true).mode("test.app", "news", NotificationCloudRules.Kind.ORDINARY));
    }
}
