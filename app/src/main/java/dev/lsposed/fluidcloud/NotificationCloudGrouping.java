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

    static String identity(StatusBarNotification sbn) {
        String conversation = sbn.getNotification().getShortcutId();
        if (conversation == null || conversation.isEmpty()) conversation = sbn.getNotification().getGroup();
        return sbn.getPackageName() + "\n" + (conversation == null ? "" : conversation);
    }
    static List<List<NotificationCloudStore.Entry<StatusBarNotification>>> groups(
            List<NotificationCloudStore.Entry<StatusBarNotification>> entries, boolean enabled) {
        var result = new LinkedHashMap<String, List<NotificationCloudStore.Entry<StatusBarNotification>>>();
        for (var entry : entries) result.computeIfAbsent(enabled ? identity(entry.value()) : entry.key(), ignored -> new ArrayList<>()).add(entry);
        return List.copyOf(result.values());
    }
    private NotificationCloudGrouping() { }
}
