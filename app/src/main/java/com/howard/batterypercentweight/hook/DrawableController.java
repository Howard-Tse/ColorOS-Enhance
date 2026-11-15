package com.howard.batterypercentweight.hook;

import com.howard.batterypercentweight.TypographySettings;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Objects;

interface UiDispatcher {
    void dispatch(Runnable action);
}

public final class DrawableController {
    private final DrawableRegistry registry;
    private final DrawableMutator mutator;
    private final UiDispatcher dispatcher;
    private final ErrorLogger errorLogger;
    private volatile TypographySettings snapshot;

    public DrawableController(
            DrawableRegistry registry,
            DrawableMutator mutator,
            UiDispatcher dispatcher,
            TypographySettings initialSettings,
            ErrorLogger errorLogger) {
        this.registry = Objects.requireNonNull(registry);
        this.mutator = Objects.requireNonNull(mutator);
        this.dispatcher = Objects.requireNonNull(dispatcher);
        this.errorLogger = Objects.requireNonNull(errorLogger);
        this.snapshot = normalize(initialSettings);
    }

    public void onConstructed(
            Object drawable,
            Field paintField,
            Method boundsUpdater,
            Method contextGetter) {
        float density = mutator.resolveDensity(drawable, contextGetter);
        DrawableRegistry.Entry entry = registry.register(
                drawable, paintField, boundsUpdater, contextGetter, density);
        DrawableRegistry.Entry.InitialBaselineCapture capture =
                entry.beginInitialBaselineCapture();
        if (capture == null) {
            return;
        }
        DrawableMutator.InitialBaseline baseline =
                mutator.captureInitialBaseline(drawable, paintField);
        entry.completeInitialBaselineCapture(capture, baseline);
        refreshEntry(drawable, entry);
    }

    public void afterSystemFontChanged(
            Object drawable, Field paintField, String systemVariation) {
        DrawableRegistry.Entry entry = registry.find(drawable);
        if (entry == null) {
            return;
        }
        DrawableRegistry.Entry.FontBaselineCapture capture =
                entry.beginFontBaselineCapture();
        Object baseline = mutator.captureFontBaseline(
                drawable, paintField, systemVariation);
        entry.completeFontBaselineCapture(capture, baseline);
        refreshEntry(drawable, entry);
    }

    public void refresh(TypographySettings settings) {
        TypographySettings normalized = normalize(settings);
        TypographySettings previous = snapshot;
        snapshot = normalized;
        boolean positionChanged = effectiveHorizontal(previous)
                        != effectiveHorizontal(normalized)
                || effectiveVertical(previous) != effectiveVertical(normalized);
        dispatcher.dispatch(() -> {
            TypographySettings latest = snapshot;
            registry.forEachLive(entry ->
                    mutateEntrySafely(entry.drawable(), entry, latest, positionChanged));
        });
    }

    public float adjustHorizontal(Object drawable, float original) {
        DrawableRegistry.Entry entry = registry.find(drawable);
        TypographySettings settings = snapshot;
        if (entry == null
                || !validDensity(entry.density())
                || !settings.appliesHorizontalOffset()) {
            return original;
        }
        return PositionAdjustment.horizontal(
                original, settings.horizontalOffsetTenthsDp(), entry.density());
    }

    public float adjustVertical(Object drawable, float original) {
        DrawableRegistry.Entry entry = registry.find(drawable);
        TypographySettings settings = snapshot;
        if (entry == null
                || !validDensity(entry.density())
                || !settings.appliesVerticalOffset()) {
            return original;
        }
        return PositionAdjustment.vertical(
                original, settings.verticalOffsetTenthsDp(), entry.density());
    }

    private void refreshEntry(
            Object drawable,
            DrawableRegistry.Entry entry) {
        dispatcher.dispatch(() -> mutateEntrySafely(drawable, entry, snapshot, false));
    }

    private void mutateEntrySafely(
            Object drawable,
            DrawableRegistry.Entry entry,
            TypographySettings settings,
            boolean positionChanged) {
        try {
            mutateEntry(drawable, entry, settings, positionChanged);
        } catch (RuntimeException | LinkageError error) {
            errorLogger.log("Unable to update registered battery drawable", error);
        }
    }

    private void mutateEntry(
            Object drawable,
            DrawableRegistry.Entry entry,
            TypographySettings settings,
            boolean positionChanged) {
        if (drawable == null) {
            return;
        }
        boolean paintChanged = updateWeight(drawable, entry, settings);
        paintChanged |= updateTextSize(drawable, entry, settings);
        if (paintChanged && entry.boundsUpdater() != null) {
            mutator.recomputeTextBounds(drawable, entry.boundsUpdater());
        }
        if (paintChanged || positionChanged) {
            mutator.invalidate(drawable);
        }
    }

    private boolean updateWeight(
            Object drawable,
            DrawableRegistry.Entry entry,
            TypographySettings settings) {
        synchronized (entry) {
            if (settings.appliesWeight()) {
                if (entry.fontBaseline() == null
                        || (entry.weightApplied()
                        && entry.appliedWeight() == settings.weight())) {
                    return false;
                }
                boolean changed = mutator.applyWeight(
                        drawable, entry.field(), settings.weight());
                if (changed) {
                    entry.setWeightApplied(settings.weight());
                }
                return changed;
            }
            if (!entry.weightApplied() || entry.fontBaseline() == null) {
                return false;
            }
            boolean changed = mutator.restoreWeight(
                    drawable, entry.field(), entry.fontBaseline());
            if (changed) {
                entry.clearWeightApplied();
            }
            return changed;
        }
    }

    private boolean updateTextSize(
            Object drawable,
            DrawableRegistry.Entry entry,
            TypographySettings settings) {
        synchronized (entry) {
            Float baseline = entry.textSizeBaselinePx();
            if (settings.appliesTextSize()) {
                if (baseline == null
                        || !validDensity(entry.density())
                        || (entry.textSizeApplied()
                        && entry.appliedTextSizeTenthsDp()
                        == settings.textSizeTenthsDp())) {
                    return false;
                }
                boolean changed = mutator.applyTextSize(
                        drawable,
                        entry.field(),
                        settings.textSizeTenthsDp(),
                        entry.density());
                if (changed) {
                    entry.setTextSizeApplied(settings.textSizeTenthsDp());
                }
                return changed;
            }
            if (!entry.textSizeApplied() || baseline == null) {
                return false;
            }
            boolean changed = mutator.restoreTextSize(
                    drawable, entry.field(), baseline);
            if (changed) {
                entry.clearTextSizeApplied();
            }
            return changed;
        }
    }

    private static TypographySettings normalize(TypographySettings settings) {
        Objects.requireNonNull(settings);
        return new TypographySettings(
                settings.enabled(),
                settings.weightEnabled(),
                settings.weight(),
                settings.textSizeEnabled(),
                settings.textSizeTenthsDp(),
                settings.horizontalOffsetEnabled(),
                settings.horizontalOffsetTenthsDp(),
                settings.verticalOffsetEnabled(),
                settings.verticalOffsetTenthsDp());
    }

    private static int effectiveHorizontal(TypographySettings settings) {
        return settings.appliesHorizontalOffset()
                ? settings.horizontalOffsetTenthsDp()
                : 0;
    }

    private static int effectiveVertical(TypographySettings settings) {
        return settings.appliesVerticalOffset()
                ? settings.verticalOffsetTenthsDp()
                : 0;
    }

    private static boolean validDensity(float density) {
        return Float.isFinite(density) && density > 0.0f;
    }
}
