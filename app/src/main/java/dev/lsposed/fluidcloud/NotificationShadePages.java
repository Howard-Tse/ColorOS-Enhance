package dev.lsposed.fluidcloud;

import android.service.notification.StatusBarNotification;
import dev.howard.colorosenhance.notifications.NotificationCloudStore.Entry;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Main-thread selection over the members actually projected into the notification list. */
final class NotificationShadePages {
    record Page(List<Entry<StatusBarNotification>> members, int index, boolean locked, Entry<StatusBarNotification> anchor) {
        Page { members = List.copyOf(members); }
        String rowKey() { return anchor.key(); }
        Entry<StatusBarNotification> member() { return members.get(index); }
        boolean historical() { return NotificationCloudHistory.historical(member()); }
        int number() { return members.size() - index; } // Newest first, like the old group preview.
        Page at(int position) { return new Page(members, members.size() - 1 - position, locked, anchor); }
        Page step(int direction) {
            int next = index - direction;
            return Math.abs(direction) != 1 || next < 0 || next >= members.size()
                    ? null : new Page(members, next, locked, anchor);
        }
        boolean sameContent(Page other) {
            return other != null && locked == other.locked && number() == other.number()
                    && members.size() == other.members.size() && member().revision() == other.member().revision()
                    && member().key().equals(other.member().key());
        }
    }

    private final Map<String, Page> visible = new HashMap<>();
    private final Map<NotificationCloudGrouping.Identity, String> selected = new HashMap<>();

    Page resolve(List<Entry<StatusBarNotification>> group, boolean locked) {
        return resolve(group, List.of(), locked);
    }
    Page resolve(List<Entry<StatusBarNotification>> group, List<Entry<StatusBarNotification>> archive, boolean locked) {
        if (group.isEmpty()) return null;
        var ordered = group;
        if (!locked && !archive.isEmpty()) {
            ordered = new java.util.ArrayList<>(archive);
            ordered.addAll(group);
        }
        if (ordered.size() < 2) return null;
        var anchor = group.getFirst();
        String wanted = selected.get(NotificationCloudGrouping.identity(group.getFirst().value()));
        if (wanted != null) for (int i = 0; i < ordered.size(); i++)
            if (wanted.equals(ordered.get(i).key())) return new Page(ordered, i, locked, anchor);
        return new Page(ordered, ordered.size() - 1, locked, anchor);
    }
    Page current(String key) { return visible.get(key); }
    boolean isEmpty() { return visible.isEmpty(); }
    void bind(String key, Page page) {
        if (page == null) visible.remove(key);
        else visible.put(key, page);
    }
    void turned(Page page) {
        selected.put(NotificationCloudGrouping.identity(page.anchor().value()), page.member().key());
        bind(page.rowKey(), page);
    }
    void retain(Set<String> rendered) {
        visible.keySet().retainAll(rendered);
        if (selected.isEmpty()) return;
        var live = new java.util.HashSet<String>();
        for (Page page : visible.values()) live.add(page.member().key());
        selected.values().retainAll(live);
    }
    void clear() { visible.clear(); selected.clear(); }
}
