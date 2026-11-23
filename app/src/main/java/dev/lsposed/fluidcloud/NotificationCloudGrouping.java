package dev.lsposed.fluidcloud;

import android.service.notification.StatusBarNotification;
import dev.howard.colorosenhance.notifications.NotificationCloudStore;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

final class NotificationCloudGrouping {
    record RenderVersion(long representative, long latest, int count) { }

    static RenderVersion renderVersion(List<NotificationCloudStore.Entry<StatusBarNotification>> group) {
        // The collapsed template uses the representative's identity, the latest
        // notification's content/actions and the number of members. The full group
        // remains live for the menu, dismissal and original-notification handling.
        return new RenderVersion(group.getFirst().revision(), group.getLast().revision(), group.size());
    }

    record Identity(android.os.UserHandle user, String app, String conversation) { }

    static Identity identity(StatusBarNotification sbn) {
        String conversation = sbn.getNotification().getShortcutId();
        if (conversation == null || conversation.isEmpty()) conversation = sbn.getNotification().getGroup();
        return new Identity(sbn.getUser(), sbn.getPackageName(), conversation == null ? "" : conversation);
    }
    /** A cloud conversation may be only part of an Android group, or have no summary at all. */
    static boolean hasMatchingSummary(List<StatusBarNotification> live, java.util.Set<String> members,
                                      java.util.Set<String> collected) {
        if (members.isEmpty() || !collected.containsAll(members)) return false;
        String groupKey = null;
        int matched = 0;
        for (var item : live) {
            if (!members.contains(item.getKey())) continue;
            if (!item.isGroup() || groupKey != null && !groupKey.equals(item.getGroupKey())) return false;
            groupKey = item.getGroupKey();
            matched++;
        }
        if (matched != members.size()) return false;
        boolean summary = false;
        for (var item : live) {
            if (!groupKey.equals(item.getGroupKey())) continue;
            if ((item.getNotification().flags & android.app.Notification.FLAG_GROUP_SUMMARY) != 0) summary = true;
            else if (collected.contains(item.getKey()) && !members.contains(item.getKey())) return false;
        }
        return summary;
    }

    static List<List<NotificationCloudStore.Entry<StatusBarNotification>>> groups(
            List<NotificationCloudStore.Entry<StatusBarNotification>> entries, boolean enabled) {
        if (!enabled) return entries.stream().map(List::of).toList();
        var result = new LinkedHashMap<Identity, List<NotificationCloudStore.Entry<StatusBarNotification>>>();
        for (var entry : entries) result.computeIfAbsent(identity(entry.value()), ignored -> new ArrayList<>()).add(entry);
        return List.copyOf(result.values());
    }
    private NotificationCloudGrouping() { }
}
