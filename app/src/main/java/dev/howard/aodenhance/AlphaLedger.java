package dev.howard.aodenhance;

import java.util.ArrayList;
import java.util.List;
import java.util.WeakHashMap;

/** Values never reference their weak key. All access is synchronized for binder/main callbacks. */
public final class AlphaLedger<T> {
    public record Clip(int left, int top, int right, int bottom) {}
    public static final Clip EMPTY = new Clip(0, 0, 0, 0);
    public static final class Entry {
        public final String element;
        public float nativeAlpha;
        public Clip nativeClip;
        public float nativeTransitionAlpha = 1f;
        final ElementFade fade = new ElementFade();
        Entry(String element, float alpha) { this.element = element; nativeAlpha = alpha; }
    }
    private final WeakHashMap<T, Entry> entries = new WeakHashMap<>();
    public synchronized void register(T key, String element, float alpha) {
        entries.computeIfAbsent(key, ignored -> new Entry(element, alpha));
    }
    public synchronized Entry get(T key) { return entries.get(key); }
    public synchronized void request(T key, float alpha) {
        Entry entry = entries.get(key);
        if (entry != null) entry.nativeAlpha = alpha;
    }
    public synchronized List<T> keys() { return new ArrayList<>(entries.keySet()); }
    public synchronized void requestClip(T key, Clip clip) {
        Entry entry = entries.get(key);
        if (entry != null) entry.nativeClip = clip;
    }
}
