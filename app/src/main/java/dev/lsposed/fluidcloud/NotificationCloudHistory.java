package dev.lsposed.fluidcloud;

import android.app.Notification;
import android.os.Bundle;
import android.os.UserHandle;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import dev.howard.colorosenhance.notifications.NotificationCloudStore.Entry;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** In-memory text snapshots. Archived notifications never enter the live notification store. */
final class NotificationCloudHistory {
    private static final long REPLACEMENT_MS = 5000L;
    private static final int LIMIT = 100;
    private record Source(UserHandle user, String app, String channel, String conversation) { }
    private record Content(String title, String text, long messageTime) { }
    private record Seen(String key, Source source, Content content, long postTime, long observedAt, long order) { }
    private record Removed(Seen item, long time) { }
    private record Archive(String sourceKey, Source source, Entry<StatusBarNotification> entry, long order) { }

    private final Map<String, Seen> live = new HashMap<>();
    private final LinkedHashMap<String, Removed> removed = new LinkedHashMap<>();
    private final LinkedHashMap<String, Archive> archived = new LinkedHashMap<>();
    private long sequence, generation;

    static boolean historical(Entry<StatusBarNotification> entry) { return entry.revision() < 0; }
    long generation() { return generation; }

    void observe(StatusBarNotification notification, long now) {
        String key = notification.getKey();
        Source source = source(notification);
        Content content = content(notification);
        if (content == null) { live.remove(key); return; }
        Seen previous = live.get(key);
        if (previous != null && previous.source().equals(source) && previous.content().equals(content)) return;
        Seen current = new Seen(key, source, content, notification.getPostTime(), now, ++sequence);
        live.put(key, current);
        if (previous != null && !previous.content().equals(content)) save(previous);
        removed.values().removeIf(removal -> {
            if (now - removal.time() > REPLACEMENT_MS) return true;
            Seen old = removal.item();
            if (!old.source().equals(source)) return false;
            if (!old.content().equals(content)) save(old);
            return true;
        });
    }

    void removed(String key, int reason, long now) {
        Seen previous = live.remove(key);
        if (previous == null) return;
        if (reason != NotificationListenerService.REASON_APP_CANCEL && reason != NotificationListenerService.REASON_APP_CANCEL_ALL) return;
        // Some apps post the replacement before cancelling its predecessor.
        Seen replacement = live.values().stream().filter(candidate -> candidate.source().equals(previous.source())
                && candidate.order() > previous.order() && now - candidate.observedAt() <= REPLACEMENT_MS)
                .max(Comparator.comparingLong(Seen::order)).orElse(null);
        if (replacement != null) {
            if (!replacement.content().equals(previous.content())) save(previous);
            return;
        }
        removed.put(key, new Removed(previous, now));
        while (removed.size() > LIMIT) removed.pollFirstEntry();
    }

    private void save(Seen previous) {
        String key = "cloud-history:" + previous.order();
        if (archived.containsKey(key)) return;
        Source source = previous.source();
        Notification preview = new Notification();
        preview.extras = new Bundle();
        preview.extras.putString(Notification.EXTRA_TITLE, previous.content().title());
        preview.extras.putString(Notification.EXTRA_TEXT, previous.content().text());
        long time = previous.content().messageTime() > 0 ? previous.content().messageTime() : previous.postTime();
        preview.when = time;
        StatusBarNotification snapshot = new StatusBarNotification(source.app(), source.app(), 0, null, 0, 0, 0,
                preview, source.user(), time);
        archived.put(key, new Archive(previous.key(), source,
                new Entry<>(key, source.app(), source.channel(), snapshot, -previous.order(), true),
                previous.order()));
        while (archived.size() > LIMIT) archived.pollFirstEntry();
        generation++;
    }

    List<Entry<StatusBarNotification>> forGroup(List<Entry<StatusBarNotification>> group) {
        if (archived.isEmpty() || group.isEmpty()) return List.of();
        var groupSources = new GroupSources(group);
        return archived.values().stream().filter(archive -> groupSources.matches(archive.sourceKey(), archive.source()))
                .sorted(Comparator.comparingLong(Archive::order)).map(Archive::entry).toList();
    }

    boolean contains(Entry<StatusBarNotification> entry) {
        Archive current = archived.get(entry.key());
        return current != null && current.entry() == entry;
    }

    void clearGroup(List<Entry<StatusBarNotification>> group) {
        var groupSources = new GroupSources(group);
        if (archived.values().removeIf(archive -> groupSources.matches(archive.sourceKey(), archive.source()))) generation++;
        removed.values().removeIf(removal -> groupSources.matches(removal.item().key(), removal.item().source()));
    }

    private static final class GroupSources {
        private final Set<String> keys = new java.util.HashSet<>();
        private final Set<Source> sources = new java.util.HashSet<>();

        GroupSources(List<Entry<StatusBarNotification>> group) {
            for (var item : group) {
                keys.add(item.key());
                sources.add(source(item.value()));
            }
        }

        boolean matches(String key, Source source) {
            // A single Android notification slot may be reused for different conversations.
            return keys.contains(key) || sources.contains(source);
        }
    }

    void retainLive(Set<String> keys) { live.keySet().retainAll(keys); }
    void forgetLive() { live.clear(); removed.clear(); }
    void clear() {
        forgetLive();
        if (!archived.isEmpty()) { archived.clear(); generation++; }
    }

    private static Source source(StatusBarNotification sbn) {
        return new Source(sbn.getUser(), sbn.getPackageName(), sbn.getNotification().getChannelId(),
                NotificationCloudGrouping.identity(sbn).conversation());
    }

    private static Content content(StatusBarNotification sbn) {
        Notification n = sbn.getNotification();
        Bundle extras = n.extras;
        if (sbn.isOngoing() || extras == null || Notification.CATEGORY_CALL.equals(n.category)
                || (n.flags & Notification.FLAG_GROUP_SUMMARY) != 0
                || extras.getInt(Notification.EXTRA_PROGRESS_MAX) > 0
                || extras.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE)) return null;
        String title = text(extras.getCharSequence(Notification.EXTRA_TITLE), 180);
        String body = text(extras.getCharSequence(Notification.EXTRA_BIG_TEXT), 1600);
        long messageTime = 0L;
        var messages = extras.getParcelableArray(Notification.EXTRA_MESSAGES, Bundle.class);
        if (messages != null && messages.length > 0) {
            Bundle last = messages[messages.length - 1];
            if (last != null) {
                String latest = text(last.getCharSequence("text"), 1600);
                if (!latest.isEmpty()) { body = latest; messageTime = last.getLong("time"); }
            }
        }
        if (body.isEmpty()) {
            var lines = extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES);
            if (lines != null && lines.length > 0) {
                var joined = new StringBuilder();
                for (CharSequence line : lines) {
                    if (joined.length() >= 1600) break;
                    if (line == null || line.length() == 0) continue;
                    if (!joined.isEmpty()) joined.append('\n');
                    joined.append(line, 0, Math.min(line.length(), 1600 - joined.length()));
                }
                body = joined.toString();
            }
        }
        if (body.isEmpty()) body = text(extras.getCharSequence(Notification.EXTRA_TEXT), 1600);
        return body.isEmpty() && title.isEmpty() ? null : new Content(title, body, messageTime);
    }

    private static String text(CharSequence value, int limit) {
        return value == null ? "" : value.subSequence(0, Math.min(limit, value.length())).toString().strip();
    }
}
