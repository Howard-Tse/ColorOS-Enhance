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
                NotificationCloudRules.AfterOpen.KEEP, true, false, false, false, Map.of(), true, false, false);
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

    @Test public void expandedSwipeDismissDefaultsOnAndCanBeDisabledWithoutDisablingGrouping() throws Exception {
        var old = new org.json.JSONObject(NotificationCloudRules.DEFAULT.encode());
        old.remove("swipeDismiss");
        var rules = NotificationCloudRules.parse(old.toString());
        assertTrue(rules.swipeDismiss());
        var disabled = rules.with("swipeDismiss", false).editApp("test.app", true, false, null, Map.of());
        var reloaded = NotificationCloudRules.parse(disabled.encode());
        assertFalse(reloaded.swipeDismiss());
        assertTrue(reloaded.groupApps());
    }

    @Test public void keepingOriginalsCanSuppressOnlyBannersAndRemembersTheChoice() throws Exception {
        var option = NotificationSettingsOptions.ALL.stream()
                .filter(row -> row.key().equals("suppressOriginalBanner")).findFirst().orElseThrow();
        var old = new org.json.JSONObject(NotificationCloudRules.DEFAULT.with("original", "KEEP").encode());
        old.remove(option.key());
        var original = NotificationCloudRules.parse(old.toString());
        assertTrue(option.available(original));
        assertFalse(original.replaceHeadsUp());

        var enabled = option.change(original, 1).editApp("test.app", true, false, null, Map.of())
                .editClone("test.app", 12, new NotificationCloudRules.CloneRule(true, original.mainRule("test.app")));
        var restored = NotificationCloudRules.parse(enabled.encode());
        assertTrue(restored.replaceHeadsUp());
        assertFalse(restored.hideOriginal());
        assertTrue(restored.keepAlerts());
        for (String mode : new String[]{"COLLECT", "HIDE"}) {
            var replaced = restored.with("original", mode);
            assertFalse(option.available(replaced));
            assertTrue(option.change(replaced, 0).replaceHeadsUp());
            assertEquals(1, option.selection(replaced.with("original", "KEEP")));
        }
        assertFalse(option.change(restored, 0).replaceHeadsUp());
    }

    @Test public void historyIsOptInAndSurvivesAppRuleEdits() {
        assertFalse(NotificationCloudRules.DEFAULT.history());
        var chosen = NotificationCloudRules.DEFAULT.with("history", true)
                .editApp("test.app", true, false, null, Map.of());
        assertTrue(NotificationCloudRules.parse(chosen.encode()).history());
    }
    @Test public void malformedConfigurationFallsBackWithoutBroadeningEligibility() {
        assertNull(NotificationCloudRules.parse("{}")); assertNull(NotificationCloudRules.parse("x"));
        assertNull(NotificationCloudRules.parse(NotificationCloudRules.DEFAULT.encode().replace("CAPSULE", "INVALID")));
        assertEquals(NotificationCloudRules.DEFAULT, NotificationCloudRules.from(Map.of(NotificationCloudRules.KEY, "{}")));
        assertFalse(dev.howard.colorosenhance.SettingsCoordinator.validValue("cloud", NotificationCloudRules.KEY, "{}"));
        assertTrue(dev.howard.colorosenhance.SettingsCoordinator.validValue("cloud", NotificationCloudRules.KEY, NotificationCloudRules.DEFAULT.encode()));
    }

    @Test public void clonesInheritLiveRulesOrKeepTheirOwnListChannelAndFloatingSettings() {
        String pkg = "test.app";
        int first = 14;
        int second = 15;
        var independent = new NotificationCloudRules.AppRule(true, true, NotificationCloudRules.Floating.ON,
                NotificationCloudRules.Mode.NATIVE, Map.of("test", NotificationCloudRules.Mode.EXPAND));
        var rules = NotificationCloudRules.DEFAULT.editClone(pkg, first, new NotificationCloudRules.CloneRule(false, independent));
        var ordinary = NotificationCloudRules.Kind.ORDINARY;
        assertFalse(rules.accepts(pkg));
        assertTrue(rules.accepts(pkg, first));
        assertFalse(rules.accepts(pkg, second));
        assertTrue(rules.floating(pkg, first));
        assertEquals(NotificationCloudRules.Mode.EXPAND, rules.mode(pkg, first, "test", ordinary));
        assertEquals(NotificationCloudRules.Mode.NATIVE, rules.mode(pkg, first, "other", ordinary));
        assertEquals(NotificationCloudRules.Mode.NATIVE, rules.mode(pkg, second, "test", ordinary));
        assertFalse(rules.with("denylist", true).accepts(pkg, first));

        var following = rules.editClone(pkg, first, new NotificationCloudRules.CloneRule(true, independent))
                .editApp(pkg, true, false, null, Map.of(pkg + "/test", NotificationCloudRules.Mode.CAPSULE));
        assertEquals(NotificationCloudRules.Mode.CAPSULE, following.mode(pkg, first, "test", ordinary));
        assertEquals(NotificationCloudRules.Mode.CAPSULE, following.mode(pkg, second, "test", ordinary));
        assertFalse(following.floating(pkg, first));
        var reloaded = NotificationCloudRules.parse(following.encode());
        assertEquals(following, reloaded);
        assertEquals(independent, reloaded.cloneRule(pkg, first).app());
        assertNull(reloaded.cloneRule(pkg, second));
    }
}
