package dev.howard.colorosenhance.notifications;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Main-thread state. Original presentation changes only after a matching native view is ready. */
public final class NotificationCloudStore<T> {
    public record Entry<T>(String key, String app, String channel, T value, long revision, boolean ready) { }
    private final Map<String, Entry<T>> entries = new LinkedHashMap<>();
    private final Set<String> returned = new java.util.HashSet<>();
    private long revision;
    public Entry<T> put(String key, String app, String channel, T value) {
        Entry<T> previous = entries.get(key);
        Entry<T> entry = new Entry<>(key, app, channel, value, ++revision, previous != null && previous.ready());
        entries.put(key, entry); return entry;
    }
    public boolean ready(String key, long generation) {
        Entry<T> entry = entries.get(key);
        if (entry == null || entry.revision() != generation || returned.contains(key)) return false;
        if (!entry.ready()) entries.put(key, new Entry<>(key, entry.app(), entry.channel(), entry.value(), generation, true));
        return true;
    }
    public void failed(String key) {
        Entry<T> entry = entries.get(key);
        if (entry != null) entries.put(key, new Entry<>(key, entry.app(), entry.channel(), entry.value(), entry.revision(), false));
    }
    public void remove(String key) { forgetView(key); returned.remove(key); }
    public void forgetView(String key) { entries.remove(key); }
    public void retainSuppressed(Set<String> liveKeys) { returned.retainAll(liveKeys); }
    public void forgetViews() { entries.clear(); }
    public void returnOriginal(String key) { returned.add(key); failed(key); }
    public boolean suppressed(String key) { return returned.contains(key); }
    public Entry<T> get(String key) { return entries.get(key); }
    public boolean ready(String key) { Entry<T> entry = entries.get(key); return entry != null && entry.ready() && !returned.contains(key); }
    public List<Entry<T>> entries() { return List.copyOf(entries.values()); }
    public void clear() { entries.clear(); returned.clear(); }
}
