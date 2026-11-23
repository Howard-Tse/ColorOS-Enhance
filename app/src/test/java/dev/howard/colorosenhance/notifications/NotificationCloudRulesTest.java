package dev.howard.colorosenhance.notifications;

import java.util.Map;
import java.util.Set;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class) @Config(sdk = 36)
public class NotificationCloudRulesTest {
    @Test public void defaultsAreEmptyAllowlistAndQuietNative() {
        var rules = NotificationCloudRules.DEFAULT;
        assertFalse(rules.accepts("test.app"));
        assertEquals(NotificationCloudRules.Mode.NATIVE, rules.silent());
        assertEquals(NotificationCloudRules.Original.COLLECT, rules.original());
        assertFalse(rules.floating()); assertFalse(rules.directOpen()); assertTrue(rules.groupApps());
    }
    @Test public void channelThenAppThenTypeAndNeverBypassesEligibility() {
        var rules = new NotificationCloudRules(false, Set.of("test.app"), Set.of(), NotificationCloudRules.Mode.CAPSULE,
                NotificationCloudRules.Mode.CAPSULE, NotificationCloudRules.Mode.NATIVE, NotificationCloudRules.Mode.NATIVE,
                NotificationCloudRules.Original.COLLECT, NotificationCloudRules.Style.AUTO, true, false, false,
                Map.of("test.app", NotificationCloudRules.Mode.EXPAND, "test.app/quiet", NotificationCloudRules.Mode.NATIVE,
                        "other.app", NotificationCloudRules.Mode.EXPAND), Map.of(), NotificationCloudRules.CapsuleText.CONTENT,
                NotificationCloudRules.AfterOpen.KEEP, true, false, false, false);
        assertEquals(NotificationCloudRules.Mode.EXPAND, rules.mode("test.app", "news", NotificationCloudRules.Kind.SILENT));
        assertEquals(NotificationCloudRules.Mode.NATIVE, rules.mode("test.app", "quiet", NotificationCloudRules.Kind.ORDINARY));
        assertEquals(NotificationCloudRules.Mode.NATIVE, rules.mode("other.app", "news", NotificationCloudRules.Kind.ORDINARY));
        assertEquals(rules, NotificationCloudRules.parse(rules.encode()));
    }
    @Test public void lockRetentionIsOptInAndSurvivesAppRuleEdits() throws Exception {
        var legacy = new org.json.JSONObject(NotificationCloudRules.DEFAULT.encode());
        legacy.remove("retainOnLock");
        var rules = NotificationCloudRules.parse(legacy.toString());
        var option = NotificationSettingsOptions.ALL.stream().filter(item -> item.key().equals("retainOnLock")).findFirst().orElseThrow();
        assertEquals(0, option.selection(rules));
        var enabled = option.change(rules, 1).editApp("test.app", true, false, null, Map.of());
        assertTrue(NotificationCloudRules.parse(enabled.encode()).retainOnLock());
        assertEquals(0, option.selection(option.change(enabled, 0)));
    }
    @Test public void lockCardsRequireRetentionAndSurviveAppEdits() throws Exception {
        var old = new org.json.JSONObject(NotificationCloudRules.DEFAULT.encode());
        old.remove("lockCards");
        var rules = NotificationCloudRules.parse(old.toString());
        var option = NotificationSettingsOptions.ALL.stream().filter(row -> row.key().equals("lockCards")).findFirst().orElseThrow();
        assertFalse(rules.lockCards());
        assertFalse(option.available(rules));
        rules = option.change(rules.with("retainOnLock", true), 1).editApp("test.app", true, false, null, Map.of());
        assertTrue(option.available(rules));
        assertTrue(NotificationCloudRules.parse(rules.encode()).lockCards());
        assertFalse(rules.shadeCards());
    }
    @Test public void malformedConfigurationFallsBackWithoutBroadeningEligibility() {
        assertNull(NotificationCloudRules.parse("{}")); assertNull(NotificationCloudRules.parse("x"));
        assertNull(NotificationCloudRules.parse(NotificationCloudRules.DEFAULT.encode().replace("CAPSULE", "INVALID")));
        assertEquals(NotificationCloudRules.DEFAULT, NotificationCloudRules.from(Map.of(NotificationCloudRules.KEY, "{}")));
        assertFalse(dev.howard.colorosenhance.SettingsCoordinator.validValue("cloud", NotificationCloudRules.KEY, "{}"));
        assertTrue(dev.howard.colorosenhance.SettingsCoordinator.validValue("cloud", NotificationCloudRules.KEY, NotificationCloudRules.DEFAULT.encode()));
    }
}
