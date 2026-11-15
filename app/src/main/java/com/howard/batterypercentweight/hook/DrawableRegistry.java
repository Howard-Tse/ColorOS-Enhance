package com.howard.batterypercentweight.hook;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

public final class DrawableRegistry {
    private final CopyOnWriteArrayList<Entry> entries = new CopyOnWriteArrayList<>();
    private final ReferenceFactory referenceFactory;

    public DrawableRegistry() {
        this(WeakReference::new);
    }

    DrawableRegistry(ReferenceFactory referenceFactory) {
        this.referenceFactory = referenceFactory;
    }

    Entry register(
            Object drawable,
            Field paintField,
            Method boundsUpdater,
            Method contextGetter,
            float density) {
        Entry candidate = new Entry(
                referenceFactory.create(drawable),
                paintField,
                boundsUpdater,
                contextGetter,
                density);
        synchronized (this) {
            Entry existing = findLocked(drawable);
            if (existing != null) {
                return existing;
            }
            entries.add(candidate);
            return candidate;
        }
    }

    Entry find(Object drawable) {
        synchronized (this) {
            return findLocked(drawable);
        }
    }

    void forEachLive(Consumer<Entry> consumer) {
        for (Entry entry : entries) {
            Object drawable = entry.drawable();
            if (drawable == null) {
                entries.remove(entry);
            } else {
                entry.withPinnedDrawable(drawable, () -> consumer.accept(entry));
            }
        }
    }

    int trackedCount() {
        return entries.size();
    }

    private Entry findLocked(Object drawable) {
        for (Entry entry : entries) {
            Object existing = entry.drawable();
            if (existing == null) {
                entries.remove(entry);
            } else if (existing == drawable) {
                return entry;
            }
        }
        return null;
    }

    static final class Entry {
        private final WeakReference<Object> reference;
        private final Field field;
        private final float density;
        private final Method boundsUpdater;
        private final Method contextGetter;
        private final ThreadLocal<Object> pinnedDrawable = new ThreadLocal<>();
        private volatile Object fontBaseline;
        private volatile Float textSizeBaselinePx;
        private volatile boolean weightApplied;
        private volatile boolean textSizeApplied;
        private int appliedWeight;
        private int appliedTextSizeTenthsDp;
        private long fontBaselineRevision;
        private long initialCaptureSequence;
        private boolean initialCapturePending;

        private Entry(
                WeakReference<Object> reference,
                Field field,
                Method boundsUpdater,
                Method contextGetter,
                float density) {
            this.reference = reference;
            this.field = field;
            this.boundsUpdater = boundsUpdater;
            this.contextGetter = contextGetter;
            this.density = density;
        }

        Object drawable() {
            Object pinned = pinnedDrawable.get();
            return pinned != null ? pinned : reference.get();
        }

        Field field() {
            return field;
        }

        float density() {
            return density;
        }

        Method boundsUpdater() {
            return boundsUpdater;
        }

        Method contextGetter() {
            return contextGetter;
        }

        Object fontBaseline() {
            return fontBaseline;
        }

        Float textSizeBaselinePx() {
            return textSizeBaselinePx;
        }

        boolean weightApplied() {
            return weightApplied;
        }

        int appliedWeight() {
            return appliedWeight;
        }

        void setWeightApplied(int weight) {
            weightApplied = true;
            appliedWeight = weight;
        }

        void clearWeightApplied() {
            weightApplied = false;
        }

        boolean textSizeApplied() {
            return textSizeApplied;
        }

        int appliedTextSizeTenthsDp() {
            return appliedTextSizeTenthsDp;
        }

        void setTextSizeApplied(int tenthsDp) {
            textSizeApplied = true;
            appliedTextSizeTenthsDp = tenthsDp;
        }

        void clearTextSizeApplied() {
            textSizeApplied = false;
        }

        synchronized InitialBaselineCapture beginInitialBaselineCapture() {
            if (initialCapturePending
                    || (fontBaseline != null && textSizeBaselinePx != null)) {
                return null;
            }
            initialCapturePending = true;
            return new InitialBaselineCapture(
                    ++initialCaptureSequence, fontBaselineRevision);
        }

        synchronized void completeInitialBaselineCapture(
                InitialBaselineCapture capture,
                DrawableMutator.InitialBaseline baseline) {
            if (capture.sequence != initialCaptureSequence) {
                return;
            }
            initialCapturePending = false;
            if (baseline == null) {
                return;
            }
            if (textSizeBaselinePx == null && Float.isFinite(baseline.textSizePx())) {
                textSizeBaselinePx = baseline.textSizePx();
            }
            if (capture.fontRevision == fontBaselineRevision
                    && fontBaseline == null
                    && baseline.fontState() != null) {
                fontBaseline = baseline.fontState();
            }
        }

        synchronized FontBaselineCapture beginFontBaselineCapture() {
            weightApplied = false;
            return new FontBaselineCapture(++fontBaselineRevision);
        }

        synchronized void completeFontBaselineCapture(
                FontBaselineCapture capture, Object baseline) {
            if (capture.revision == fontBaselineRevision && baseline != null) {
                fontBaseline = baseline;
            }
        }

        private void withPinnedDrawable(Object drawable, Runnable action) {
            Object previous = pinnedDrawable.get();
            pinnedDrawable.set(drawable);
            try {
                action.run();
            } finally {
                if (previous == null) {
                    pinnedDrawable.remove();
                } else {
                    pinnedDrawable.set(previous);
                }
            }
        }

        static final class InitialBaselineCapture {
            private final long sequence;
            private final long fontRevision;

            private InitialBaselineCapture(long sequence, long fontRevision) {
                this.sequence = sequence;
                this.fontRevision = fontRevision;
            }
        }

        static final class FontBaselineCapture {
            private final long revision;

            private FontBaselineCapture(long revision) {
                this.revision = revision;
            }
        }
    }

    @FunctionalInterface
    interface ReferenceFactory {
        WeakReference<Object> create(Object value);
    }
}
