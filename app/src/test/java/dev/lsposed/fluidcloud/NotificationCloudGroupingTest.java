package dev.lsposed.fluidcloud;

import android.app.Notification;
import android.os.UserHandle;
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
        return item(pkg, id, conversation, 12345);
    }

    private NotificationCloudStore.Entry<StatusBarNotification> item(String pkg, int id, String conversation, int uid) {
        var n = new Notification.Builder(RuntimeEnvironment.getApplication(), "test").setSmallIcon(android.R.drawable.stat_notify_more)
                .setContentTitle("Test").setShortcutId(conversation).build();
        var sbn = new StatusBarNotification(pkg, pkg, id, null, uid, 0, 0, n, UserHandle.getUserHandleForUid(uid), id);
        return new NotificationCloudStore.Entry<>(sbn.getKey(), pkg, "test", sbn, id + 1, false);
    }
    @Test public void groupsAreSeparateForAppsAndConversationsAndPreserveRepresentative() {
        var a = item("first.app", 1, "one"); var b = item("first.app", 2, "one");
        var c = item("first.app", 3, "two"); var d = item("second.app", 4, "one");
        var groups = NotificationCloudGrouping.groups(List.of(a, b, c, d), true);
        assertEquals(3, groups.size()); assertEquals(List.of(a, b), groups.get(0)); assertSame(a, groups.get(0).get(0));
    }
    @Test public void mainAppAndEachCloneKeepSeparateGroupsAndRenderIdentity() {
        var main = item("test.app", 1, "one");
        var firstClone = item("test.app", 1, "one", 1_112_345);
        var secondClone = item("test.app", 1, "one", 2_112_345);
        var secondCloneUpdate = item("test.app", 2, "one", 2_112_345);
        var groups = NotificationCloudGrouping.groups(List.of(main, firstClone, secondClone, secondCloneUpdate), true);
        assertEquals(List.of(List.of(main), List.of(firstClone), List.of(secondClone, secondCloneUpdate)), groups);
        var render = NotificationCloudActions.copyMember(groups.getLast(), null);
        assertEquals(secondClone.key(), render.getKey());
        assertEquals(secondClone.value().getUser(), render.getUser());
        assertEquals(secondClone.value().getUid(), render.getUid());
    }
    @Test public void cloneBadgeAppearsOnlyOnTheRenderedCloneTitle() {
        var owner = item("test.app", 1, null).value();
        var clone = item("test.app", 1, null, 1_112_345).value();
        var rendered = clone.clone();
        var badge = new NotificationCloneBadge(RuntimeEnvironment.getApplication());
        badge.decorate(owner, -1);
        badge.decorate(rendered, 11);
        assertEquals("Test", owner.getNotification().extras.getCharSequence(Notification.EXTRA_TITLE).toString());
        assertEquals("Test", clone.getNotification().extras.getCharSequence(Notification.EXTRA_TITLE).toString());
        assertEquals("Test\u00a0分身", rendered.getNotification().extras.getCharSequence(Notification.EXTRA_TITLE).toString());
        assertNull(owner.getNotification().extras.getParcelable(NotificationCloneBadge.CAPSULE_ICON));
        assertNull(clone.getNotification().extras.getParcelable(NotificationCloneBadge.CAPSULE_ICON));
        var first = rendered.getNotification().extras.getParcelable(
                NotificationCloneBadge.CAPSULE_ICON, android.graphics.drawable.Icon.class);
        assertNotNull(first);
        var next = clone.clone();
        badge.decorate(next, 11);
        assertSame(first, next.getNotification().extras.getParcelable(NotificationCloneBadge.CAPSULE_ICON,
                android.graphics.drawable.Icon.class));
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

    private static StatusBarNotification grouped(int id, String group, boolean summary) {
        var context = RuntimeEnvironment.getApplication();
        var notification = new Notification.Builder(context, "test").setSmallIcon(android.R.drawable.stat_notify_more)
                .setGroup(group).setGroupSummary(summary).build();
        return new StatusBarNotification("source", "source", id, null, 12345, 0, 0, notification,
                android.os.Process.myUserHandle(), id);
    }

    @Test public void shadeStartsAtNewestAndFollowsTheSelectedNotificationThroughUpdates() {
        var pages = new NotificationShadePages();
        var a = item("app", 1, "thread");
        var b = item("app", 2, "thread");
        var c = item("app", 3, "thread");
        var first = pages.resolve(List.of(a, b, c), false);
        assertEquals(1, first.number());
        assertSame(c, first.member());
        assertNull(first.step(-1));
        var next = first.step(1);
        pages.turned(next);
        assertEquals(2, next.number());
        assertSame(b, next.member());
        assertNull(next.step(1).step(1));
        var newer = item("app", 4, "thread");
        var changed = pages.resolve(List.of(b, c, newer), false);
        assertSame(b, changed.member());
        assertEquals(3, changed.number());
        assertFalse(changed.sameContent(next));
        assertFalse(pages.resolve(List.of(updated(b), c, newer), false).sameContent(changed));
        assertTrue(pages.resolve(List.of(b, updated(c), newer), false).sameContent(changed));
    }

    @Test public void lockscreenOnlyPagesThroughTheAllowedProjection() {
        var pages = new NotificationShadePages();
        var a = item("app", 1, "thread");
        var b = item("app", 2, "thread");
        var c = item("app", 3, "thread");
        pages.turned(pages.resolve(List.of(a, b, c), false).step(1));
        var locked = pages.resolve(List.of(a, c), true);
        assertEquals(1, locked.number());
        assertSame(a, locked.step(1).member());
        assertNull(locked.step(1).step(1));
        assertNull(pages.resolve(List.of(c), true));
        pages.bind(a.key(), locked);
        pages.retain(java.util.Set.of(a.key()));
        assertSame(c, pages.resolve(List.of(a, b, c), false).member());
        pages.retain(java.util.Set.of());
        assertTrue(pages.isEmpty());
    }

    @Test public void pageKeepsRowIdentityButBindsTheDisplayedMembersContentAndActions() {
        var first = item("app", 1, "thread");
        var middle = item("app", 2, "thread");
        var last = item("app", 3, "thread");
        var group = List.of(first, middle, last);
        for (var entry : group) {
            var n = entry.value().getNotification();
            n.extras.putCharSequence(Notification.EXTRA_TEXT, entry.key());
            n.contentIntent = android.app.PendingIntent.getActivity(RuntimeEnvironment.getApplication(),
                    entry.value().getId(), new android.content.Intent("open"), android.app.PendingIntent.FLAG_IMMUTABLE);
            n.actions = new Notification.Action[]{new Notification.Action.Builder(null, "按钮", n.contentIntent).build()};
        }
        var page = new NotificationShadePages().resolve(group, true).step(1);
        var rendered = NotificationCloudActions.copyMember(group, page);
        assertEquals(first.key(), rendered.getKey());
        var n = rendered.getNotification();
        NotificationLockscreenCards.preparePreview(n);
        assertEquals(middle.key(), n.extras.getCharSequence(Notification.EXTRA_TEXT));
        assertEquals("Test", n.extras.getCharSequence(Notification.EXTRA_TITLE));
        assertEquals(middle.value().getNotification().contentIntent, n.contentIntent);
        assertEquals(n.contentIntent, n.actions[0].actionIntent);
        assertEquals("Test", middle.value().getNotification().extras.getCharSequence(Notification.EXTRA_TITLE));
        assertEquals(last.key(), NotificationCloudActions.copyMember(group, null).getNotification().extras.getCharSequence(Notification.EXTRA_TEXT));
    }

    @Test public void mapsNativeZeroBasedPagesToTheirNotificationWithoutChangingTheTitle() {
        var a = item("app", 1, null);
        var b = item("app", 2, null);
        var c = item("app", 3, null);
        var group = new NotificationShadePages().resolve(List.of(a, b, c), false);
        assertSame(c, group.at(0).member());
        assertSame(b, group.at(1).member());
        assertSame(a, group.at(2).member());
        assertEquals(3, group.at(2).number());
        assertEquals(group.rowKey(), group.at(1).rowKey());
    }

    @Test public void mediaPageScaleIsSymmetricWhenReversingAndReturnsToFullSize() {
        assertEquals(1f, NativeNotificationShadePaging.mediaScale(0f), .0001f);
        assertEquals(.8f, NativeNotificationShadePaging.mediaScale(.5f), .0001f);
        assertEquals(.9f, NativeNotificationShadePaging.mediaScale(.075f), .0001f);
        assertEquals(.9f, NativeNotificationShadePaging.mediaScale(.925f), .0001f);
        assertEquals(1f, NativeNotificationShadePaging.mediaScale(1f), .0001f);
    }

    @Test public void navigateToMatchingNativeSummaryEvenWithOtherGroupsPresent() {
        var summary = grouped(1, "group", true);
        var a = grouped(2, "group", false);
        var b = grouped(3, "group", false);
        var unrelated = grouped(4, "other", false);
        var selected = java.util.Set.of(a.getKey(), b.getKey());
        assertTrue(NotificationCloudGrouping.hasMatchingSummary(List.of(summary, a, unrelated, b), selected,
                java.util.Set.of(a.getKey(), b.getKey(), unrelated.getKey())));
    }

    @Test public void focusCloudMembersWhenNativeGroupIncludesAnotherConversationOrHasNoSummary() {
        var summary = grouped(1, "group", true);
        var a = grouped(2, "group", false);
        var b = grouped(3, "group", false);
        var other = grouped(4, "group", false);
        var selected = java.util.Set.of(a.getKey(), b.getKey());
        assertFalse(NotificationCloudGrouping.hasMatchingSummary(List.of(summary, a, b, other), selected,
                java.util.Set.of(a.getKey(), b.getKey(), other.getKey())));
        assertFalse(NotificationCloudGrouping.hasMatchingSummary(List.of(a, b), selected, selected));
        assertFalse(NotificationCloudGrouping.hasMatchingSummary(List.of(summary, a, b), selected, java.util.Set.of()));
        assertFalse(NotificationCloudGrouping.hasMatchingSummary(List.of(summary, b), selected, selected));
    }

    private static NotificationCloudStore.Entry<StatusBarNotification> updated(NotificationCloudStore.Entry<StatusBarNotification> prior) {
        return new NotificationCloudStore.Entry<>(prior.key(), prior.app(), prior.channel(), prior.value(), prior.revision() + 10, prior.ready());
    }
}
