package com.howard.batterypercentweight.hook;

import com.howard.batterypercentweight.TypographySettings;

import org.junit.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public final class DrawableControllerTest {
    private static final class Fixture {
        private Object percentInPaint;

        public void updateBatteryLevel() {
        }

        public android.content.Context getContext() {
            return null;
        }
    }

    @Test public void constructedDrawableImmediatelyAppliesCurrentSettingsOnDispatcher()
            throws Exception {
        FakeMutator mutator = new FakeMutator();
        RecordingDispatcher dispatcher = new RecordingDispatcher();
        DrawableController controller = controller(
                new DrawableRegistry(), mutator, dispatcher,
                settings(true, true, 566, false, 100, false, 0, false, 0));

        controller.onConstructed(new Fixture(), field(), boundsUpdater(), contextGetter());

        assertEquals(1, dispatcher.dispatchCalls);
        assertEquals(List.of(), mutator.appliedWeights);
        dispatcher.runAll();
        assertEquals(List.of(570), mutator.appliedWeights);
        assertEquals(List.of("weight", "bounds", "invalidate"), mutator.events);
    }

    @Test public void constructingSecondDrawableUpdatesOnlySecondDrawable() throws Exception {
        FakeMutator mutator = new FakeMutator();
        DrawableController controller = controller(
                new DrawableRegistry(), mutator, Runnable::run, settings(true, true, 570, false, 100, false, 0, false, 0));
        Fixture first = new Fixture();
        Fixture second = new Fixture();

        controller.onConstructed(first, field(), boundsUpdater(), contextGetter());
        controller.onConstructed(second, field(), boundsUpdater(), contextGetter());

        assertEquals(List.of(first, second), mutator.appliedDrawables);
        assertEquals(List.of(first, second), mutator.invalidatedDrawables);
    }

    @Test public void fontCallbackUpdatesOnlyChangedDrawable() throws Exception {
        FakeMutator mutator = new FakeMutator();
        DrawableController controller = controller(
                new DrawableRegistry(), mutator, Runnable::run, settings(true, true, 570, false, 100, false, 0, false, 0));
        Fixture first = new Fixture();
        Fixture second = new Fixture();
        controller.onConstructed(first, field(), boundsUpdater(), contextGetter());
        controller.onConstructed(second, field(), boundsUpdater(), contextGetter());
        mutator.appliedDrawables.clear();
        mutator.invalidatedDrawables.clear();

        controller.afterSystemFontChanged(second, field(), "'wght' 500");

        assertEquals(List.of(second), mutator.appliedDrawables);
        assertEquals(List.of(second), mutator.invalidatedDrawables);
    }

    @Test public void reverseRefreshExecutionKeepsLatestPaintAndPositionState()
            throws Exception {
        FakeMutator mutator = new FakeMutator();
        RecordingDispatcher dispatcher = new RecordingDispatcher();
        DrawableController controller = controller(
                new DrawableRegistry(), mutator, dispatcher,
                settings(true, false, 570, false, 100, false, 0, false, 0));
        Fixture drawable = new Fixture();
        controller.onConstructed(drawable, field(), boundsUpdater(), contextGetter());
        dispatcher.runAll();
        mutator.appliedWeights.clear();
        mutator.appliedTextSizes.clear();
        mutator.events.clear();
        mutator.invalidations = 0;

        controller.refresh(settings(
                true, true, 700, true, 110, false, 0, false, 0));
        controller.refresh(settings(
                true, true, 900, true, 120, true, 10, false, 0));
        dispatcher.runAllInReverseOrder();

        assertEquals(List.of(900), mutator.appliedWeights);
        assertEquals(List.of(120), mutator.appliedTextSizes);
        assertEquals(1, mutator.invalidations);
        assertEquals(-2.5f, controller.adjustHorizontal(drawable, 0.5f), 0.0f);
    }

    @Test public void reverseFontCallbackAndRefreshKeepsLatestPaintAndPositionState()
            throws Exception {
        FakeMutator mutator = new FakeMutator();
        RecordingDispatcher dispatcher = new RecordingDispatcher();
        DrawableController controller = controller(
                new DrawableRegistry(), mutator, dispatcher,
                settings(true, true, 570, true, 100, false, 0, false, 0));
        Fixture drawable = new Fixture();
        controller.onConstructed(drawable, field(), boundsUpdater(), contextGetter());
        dispatcher.runAll();
        mutator.appliedWeights.clear();
        mutator.appliedTextSizes.clear();
        mutator.events.clear();
        mutator.invalidations = 0;

        controller.afterSystemFontChanged(drawable, field(), "'wght' 500");
        controller.refresh(settings(
                true, true, 900, true, 120, true, 10, false, 0));
        dispatcher.runAllInReverseOrder();

        assertEquals(List.of(900), mutator.appliedWeights);
        assertEquals(List.of(120), mutator.appliedTextSizes);
        assertEquals(1, mutator.invalidations);
        assertEquals(-2.5f, controller.adjustHorizontal(drawable, 0.5f), 0.0f);
    }

    @Test public void runtimeFailureIsReportedAndLaterEntryStillUpdates() throws Exception {
        assertEntryFailureIsolated(new IllegalStateException("broken Paint"));
    }

    @Test public void linkageFailureIsReportedAndLaterEntryStillUpdates() throws Exception {
        assertEntryFailureIsolated(new LinkageError("missing Paint method"));
    }

    @Test public void appliesOnlyEnabledPropertiesOnConstruction() throws Exception {
        FakeMutator mutator = new FakeMutator();
        TypographySettings settings = settings(
                true, false, 900, true, 115, false, 10, false, -10);
        DrawableController controller = controller(
                new DrawableRegistry(), mutator, Runnable::run, settings);

        controller.onConstructed(new Fixture(), field(), boundsUpdater(), contextGetter());

        assertEquals(List.of(), mutator.appliedWeights);
        assertEquals(List.of(115), mutator.appliedTextSizes);
    }

    @Test public void disablingSizeRestoresOnlyCapturedSize() throws Exception {
        FakeMutator mutator = new FakeMutator();
        TypographySettings enabled = settings(
                true, false, 570, true, 110, false, 0, false, 0);
        TypographySettings disabled = settings(
                true, false, 570, false, 110, false, 0, false, 0);
        DrawableController controller = controller(
                new DrawableRegistry(), mutator, Runnable::run, enabled);

        controller.onConstructed(new Fixture(), field(), boundsUpdater(), contextGetter());
        controller.refresh(disabled);

        assertEquals(1, mutator.restoreSizeCalls);
        assertEquals(0, mutator.restoreWeightCalls);
        assertEquals(10.0f, mutator.restoredTextSizePx, 0.0f);
    }

    @Test public void disablingWeightRestoresOnlyLatestFontBaseline() throws Exception {
        FakeMutator mutator = new FakeMutator();
        TypographySettings disabled = settings(
                true, false, 570, false, 100, false, 0, false, 0);
        DrawableController controller = controller(
                new DrawableRegistry(), mutator, Runnable::run, settings(true, true, 570, false, 100, false, 0, false, 0));
        Fixture drawable = new Fixture();
        controller.onConstructed(drawable, field(), boundsUpdater(), contextGetter());
        controller.afterSystemFontChanged(drawable, field(), "'wght' 500");
        Object latestFontBaseline = mutator.lastFontBaseline;

        controller.refresh(disabled);

        assertSame(latestFontBaseline, mutator.restoredFontBaseline);
        assertEquals(1, mutator.restoreWeightCalls);
        assertEquals(0, mutator.restoreSizeCalls);
    }

    @Test public void fontCallbackDoesNotReplaceInitialTextSize() throws Exception {
        FakeMutator mutator = new FakeMutator();
        TypographySettings enabled = settings(
                true, false, 570, true, 110, false, 0, false, 0);
        TypographySettings disabled = settings(
                true, false, 570, false, 110, false, 0, false, 0);
        DrawableController controller = controller(
                new DrawableRegistry(), mutator, Runnable::run, enabled);
        Fixture drawable = new Fixture();

        controller.onConstructed(drawable, field(), boundsUpdater(), contextGetter());
        controller.afterSystemFontChanged(drawable, field(), "'wght' 500");
        controller.refresh(disabled);

        assertEquals(10.0f, mutator.restoredTextSizePx, 0.0f);
    }

    @Test public void disabledPositionControlsReturnOriginalResults() throws Exception {
        FakeMutator mutator = new FakeMutator();
        DrawableController controller = controller(
                new DrawableRegistry(), mutator, Runnable::run, settings(true, true, 570, false, 100, false, 0, false, 0));
        Fixture drawable = new Fixture();
        controller.onConstructed(drawable, field(), boundsUpdater(), contextGetter());

        assertEquals(0.5f, controller.adjustHorizontal(drawable, 0.5f), 0.0f);
        assertEquals(0.27f, controller.adjustVertical(drawable, 0.27f), 0.0f);
    }

    @Test public void enabledPositionControlsUseCapturedDensityAndExpectedSigns()
            throws Exception {
        FakeMutator mutator = new FakeMutator();
        TypographySettings settings = settings(
                true, false, 570, false, 100, true, 10, true, -10);
        DrawableController controller = controller(
                new DrawableRegistry(), mutator, Runnable::run, settings);
        Fixture drawable = new Fixture();
        controller.onConstructed(drawable, field(), boundsUpdater(), contextGetter());

        assertEquals(-2.5f, controller.adjustHorizontal(drawable, 0.5f), 0.0f);
        assertEquals(-2.73f, controller.adjustVertical(drawable, 0.27f), 0.0001f);
        assertEquals(0.5f, controller.adjustHorizontal(new Fixture(), 0.5f), 0.0f);
    }

    @Test public void invalidDensityMakesPositionAndSizeFailClosed() throws Exception {
        FakeMutator mutator = new FakeMutator();
        mutator.density = Float.NaN;
        TypographySettings settings = settings(
                true, false, 570, true, 110, true, 10, true, 10);
        DrawableController controller = controller(
                new DrawableRegistry(), mutator, Runnable::run, settings);
        Fixture drawable = new Fixture();
        controller.onConstructed(drawable, field(), boundsUpdater(), contextGetter());

        assertEquals(List.of(), mutator.appliedTextSizes);
        assertEquals(0.5f, controller.adjustHorizontal(drawable, 0.5f), 0.0f);
        assertEquals(0.27f, controller.adjustVertical(drawable, 0.27f), 0.0f);
    }

    @Test public void paintChangesRecomputeBoundsBeforeInvalidation() throws Exception {
        FakeMutator mutator = new FakeMutator();
        TypographySettings initial = settings(
                true, true, 570, true, 100, false, 0, false, 0);
        DrawableController controller = controller(
                new DrawableRegistry(), mutator, Runnable::run, initial);
        controller.onConstructed(new Fixture(), field(), boundsUpdater(), contextGetter());
        mutator.events.clear();

        controller.refresh(settings(
                true, true, 650, true, 115, false, 0, false, 0));

        assertEquals(
                List.of("weight", "size", "bounds", "invalidate"), mutator.events);
    }

    @Test public void positionOnlyRefreshInvalidatesWithoutMutatingPaint() throws Exception {
        FakeMutator mutator = new FakeMutator();
        TypographySettings initial = settings(
                true, false, 570, false, 100, false, 0, false, 0);
        DrawableController controller = controller(
                new DrawableRegistry(), mutator, Runnable::run, initial);
        controller.onConstructed(new Fixture(), field(), boundsUpdater(), contextGetter());
        mutator.events.clear();

        controller.refresh(settings(
                true, false, 570, false, 100, true, 10, false, 0));

        assertEquals(List.of("invalidate"), mutator.events);
    }

    @Test public void disabledPositionValueChangeDoesNotInvalidate() throws Exception {
        FakeMutator mutator = new FakeMutator();
        TypographySettings initial = settings(
                true, false, 570, false, 100, false, 0, false, 0);
        DrawableController controller = controller(
                new DrawableRegistry(), mutator, Runnable::run, initial);
        controller.onConstructed(new Fixture(), field(), boundsUpdater(), contextGetter());
        mutator.events.clear();

        controller.refresh(settings(
                true, false, 570, false, 100, false, 20, false, -20));

        assertEquals(List.of(), mutator.events);
    }

    @Test public void unrelatedRefreshDoesNotRewriteAppliedPaintProperties() throws Exception {
        FakeMutator mutator = new FakeMutator();
        TypographySettings initial = settings(
                true, true, 570, true, 100, false, 0, false, 0);
        DrawableController controller = controller(
                new DrawableRegistry(), mutator, Runnable::run, initial);
        controller.onConstructed(new Fixture(), field(), boundsUpdater(), contextGetter());
        mutator.events.clear();

        controller.refresh(settings(
                true, true, 570, true, 100, true, 10, false, 0));

        assertEquals(List.of("invalidate"), mutator.events);
    }

    @Test public void failedApplyIsNotMarkedForRestoration() throws Exception {
        FakeMutator mutator = new FakeMutator();
        mutator.weightApplyChanged = false;
        DrawableController controller = controller(
                new DrawableRegistry(), mutator, Runnable::run, settings(true, true, 570, false, 100, false, 0, false, 0));
        controller.onConstructed(new Fixture(), field(), boundsUpdater(), contextGetter());

        controller.refresh(settings(
                true, false, 570, false, 100, false, 0, false, 0));

        assertEquals(0, mutator.restoreWeightCalls);
    }

    @Test public void constructedDrawableCapturesInitialBaselinesOnlyOnce() throws Exception {
        FakeMutator mutator = new FakeMutator();
        DrawableController controller = controller(
                new DrawableRegistry(), mutator, Runnable::run, settings(true, true, 570, false, 100, false, 0, false, 0));
        Fixture drawable = new Fixture();

        controller.onConstructed(drawable, field(), boundsUpdater(), contextGetter());
        controller.onConstructed(drawable, field(), boundsUpdater(), contextGetter());

        assertEquals(1, mutator.initialCaptureCalls);
        assertEquals(List.of(drawable), mutator.appliedDrawables);
    }

    @Test public void concurrentConstructionCapturesInitialBaselinesOnlyOnce() throws Exception {
        InitialCaptureMutator mutator = new InitialCaptureMutator();
        DrawableController controller = controller(
                new DrawableRegistry(), mutator, Runnable::run, settings(true, true, 570, false, 100, false, 0, false, 0));
        Fixture drawable = new Fixture();
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            Future<?> first = executor.submit(() -> constructed(controller, drawable));
            assertTrue(mutator.firstCaptureStarted.await(5, TimeUnit.SECONDS));
            Future<?> second = executor.submit(() -> constructed(controller, drawable));

            second.get(5, TimeUnit.SECONDS);
            assertEquals(1, mutator.captureCalls.get());
            mutator.releaseFirstCapture.countDown();
            first.get(5, TimeUnit.SECONDS);
        } finally {
            mutator.releaseFirstCapture.countDown();
            executor.shutdownNow();
        }
    }

    @Test public void lateConstructionKeepsNewestFontAndInitialTextSize() throws Exception {
        DelayedBaselineMutator mutator = new DelayedBaselineMutator();
        DrawableRegistry registry = new DrawableRegistry();
        DrawableController controller = controller(
                registry, mutator, Runnable::run, settings(true, true, 570, false, 100, false, 0, false, 0));
        Fixture drawable = new Fixture();
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            Future<?> construction = executor.submit(() -> constructed(controller, drawable));
            assertTrue(mutator.initialCaptureStarted.await(5, TimeUnit.SECONDS));

            Future<?> font = executor.submit(() ->
                    controller.afterSystemFontChanged(drawable, uncheckedField(), "'wght' 500"));
            font.get(5, TimeUnit.SECONDS);
            mutator.releaseInitialCapture.countDown();
            construction.get(5, TimeUnit.SECONDS);
        } finally {
            mutator.releaseInitialCapture.countDown();
            executor.shutdownNow();
        }

        DrawableRegistry.Entry entry = registry.find(drawable);
        assertSame(mutator.newestFontBaseline, entry.fontBaseline());
        assertEquals(10.0f, entry.textSizeBaselinePx(), 0.0f);
    }

    @Test public void constructionDoesNotSupersedePendingFontBaseline() throws Exception {
        PendingReplacementMutator mutator = new PendingReplacementMutator();
        DrawableRegistry registry = new DrawableRegistry();
        DrawableController controller = controller(
                registry, mutator, Runnable::run, settings(true, true, 570, false, 100, false, 0, false, 0));
        Fixture drawable = new Fixture();
        controller.onConstructed(drawable, field(), boundsUpdater(), contextGetter());
        ExecutorService executor = Executors.newSingleThreadExecutor();

        try {
            Future<?> font = executor.submit(() ->
                    controller.afterSystemFontChanged(drawable, uncheckedField(), "'wght' 500"));
            assertTrue(mutator.replacementCaptureStarted.await(5, TimeUnit.SECONDS));

            controller.onConstructed(drawable, field(), boundsUpdater(), contextGetter());

            assertEquals(1, mutator.initialCaptureCalls.get());
            mutator.releaseReplacementCapture.countDown();
            font.get(5, TimeUnit.SECONDS);
        } finally {
            mutator.releaseReplacementCapture.countDown();
            executor.shutdownNow();
        }

        DrawableRegistry.Entry entry = registry.find(drawable);
        assertSame(mutator.replacementBaseline, entry.fontBaseline());
        assertEquals(10.0f, entry.textSizeBaselinePx(), 0.0f);
    }

    private static TypographySettings settings(
            boolean enabled,
            boolean weightEnabled,
            int weight,
            boolean textSizeEnabled,
            int textSizeTenthsDp,
            boolean horizontalOffsetEnabled,
            int horizontalOffsetTenthsDp,
            boolean verticalOffsetEnabled,
            int verticalOffsetTenthsDp) {
        return new TypographySettings(
                enabled, weightEnabled, weight, textSizeEnabled, textSizeTenthsDp,
                horizontalOffsetEnabled, horizontalOffsetTenthsDp,
                verticalOffsetEnabled, verticalOffsetTenthsDp);
    }

    private static DrawableController controller(
            DrawableRegistry registry,
            DrawableMutator mutator,
            UiDispatcher dispatcher,
            TypographySettings settings) {
        return new DrawableController(
                registry,
                mutator,
                dispatcher,
                settings,
                (message, error) -> {
                    throw new AssertionError(message, error);
                });
    }

    private static Field field() throws NoSuchFieldException {
        return Fixture.class.getDeclaredField("percentInPaint");
    }

    private static Field uncheckedField() {
        try {
            return field();
        } catch (NoSuchFieldException exception) {
            throw new AssertionError(exception);
        }
    }

    private static Method boundsUpdater() throws NoSuchMethodException {
        return Fixture.class.getDeclaredMethod("updateBatteryLevel");
    }

    private static Method contextGetter() throws NoSuchMethodException {
        return Fixture.class.getDeclaredMethod("getContext");
    }

    private static void constructed(DrawableController controller, Fixture drawable) {
        try {
            controller.onConstructed(drawable, field(), boundsUpdater(), contextGetter());
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError(exception);
        }
    }

    private static void assertEntryFailureIsolated(Throwable failure) throws Exception {
        FakeMutator mutator = new FakeMutator();
        RecordingErrorLogger errorLogger = new RecordingErrorLogger();
        DrawableController controller = new DrawableController(
                new DrawableRegistry(),
                mutator,
                Runnable::run,
                settings(true, false, 570, false, 100, false, 0, false, 0),
                errorLogger);
        Fixture first = new Fixture();
        Fixture second = new Fixture();
        controller.onConstructed(first, field(), boundsUpdater(), contextGetter());
        controller.onConstructed(second, field(), boundsUpdater(), contextGetter());
        mutator.failingDrawable = first;
        mutator.mutationFailure = failure;

        controller.refresh(settings(
                true, true, 700, false, 100, false, 0, false, 0));

        assertEquals(List.of(second), mutator.appliedDrawables);
        assertEquals(1, errorLogger.failures.size());
        assertSame(failure, errorLogger.failures.get(0));
    }

    private static final class RecordingDispatcher implements UiDispatcher {
        private final List<Runnable> actions = new ArrayList<>();
        private int dispatchCalls;

        @Override public void dispatch(Runnable action) {
            dispatchCalls++;
            actions.add(action);
        }

        private void runAll() {
            List<Runnable> pending = List.copyOf(actions);
            actions.clear();
            pending.forEach(Runnable::run);
        }

        private void runAllInReverseOrder() {
            List<Runnable> pending = List.copyOf(actions);
            actions.clear();
            for (int index = pending.size() - 1; index >= 0; index--) {
                pending.get(index).run();
            }
        }
    }

    private static final class RecordingErrorLogger implements ErrorLogger {
        private final List<Throwable> failures = new ArrayList<>();

        @Override public void log(String message, Throwable error) {
            failures.add(error);
        }
    }

    private static class FakeMutator implements DrawableMutator {
        private final Object initialFontBaseline = new Object();
        private final List<Integer> appliedWeights = new ArrayList<>();
        private final List<Integer> appliedTextSizes = new ArrayList<>();
        private final List<Object> appliedDrawables = new ArrayList<>();
        private final List<Object> invalidatedDrawables = new ArrayList<>();
        private final List<String> events = new ArrayList<>();
        private boolean weightApplyChanged = true;
        private boolean sizeApplyChanged = true;
        private boolean restoreWeightChanged = true;
        private boolean restoreSizeChanged = true;
        private float density = 3.0f;
        private Object lastFontBaseline;
        private Object restoredFontBaseline;
        private float restoredTextSizePx = Float.NaN;
        private int initialCaptureCalls;
        private int restoreWeightCalls;
        private int restoreSizeCalls;
        private int invalidations;
        private Object failingDrawable;
        private Throwable mutationFailure;

        @Override public InitialBaseline captureInitialBaseline(Object drawable, Field paintField) {
            initialCaptureCalls++;
            lastFontBaseline = initialFontBaseline;
            return new InitialBaseline(initialFontBaseline, 10.0f);
        }

        @Override public Object captureFontBaseline(
                Object drawable, Field paintField, String systemVariation) {
            lastFontBaseline = new Object();
            return lastFontBaseline;
        }

        @Override public float resolveDensity(Object drawable, Method contextGetter) {
            return density;
        }

        @Override public boolean applyWeight(Object drawable, Field paintField, int weight) {
            if (drawable == failingDrawable) {
                if (mutationFailure instanceof RuntimeException runtimeException) {
                    throw runtimeException;
                }
                if (mutationFailure instanceof LinkageError linkageError) {
                    throw linkageError;
                }
            }
            appliedDrawables.add(drawable);
            appliedWeights.add(weight);
            events.add("weight");
            return weightApplyChanged;
        }

        @Override public boolean restoreWeight(
                Object drawable, Field paintField, Object fontBaseline) {
            restoreWeightCalls++;
            restoredFontBaseline = fontBaseline;
            events.add("restoreWeight");
            return restoreWeightChanged;
        }

        @Override public boolean applyTextSize(
                Object drawable, Field paintField, int tenthsDp, float density) {
            appliedTextSizes.add(tenthsDp);
            events.add("size");
            return sizeApplyChanged;
        }

        @Override public boolean restoreTextSize(
                Object drawable, Field paintField, float textSizePx) {
            restoreSizeCalls++;
            restoredTextSizePx = textSizePx;
            events.add("restoreSize");
            return restoreSizeChanged;
        }

        @Override public boolean recomputeTextBounds(Object drawable, Method boundsUpdater) {
            events.add("bounds");
            return true;
        }

        @Override public void invalidate(Object drawable) {
            invalidatedDrawables.add(drawable);
            invalidations++;
            events.add("invalidate");
        }
    }

    private abstract static class NoOpMutator implements DrawableMutator {
        @Override public Object captureFontBaseline(
                Object drawable, Field paintField, String systemVariation) {
            return new Object();
        }

        @Override public float resolveDensity(Object drawable, Method contextGetter) {
            return 3.0f;
        }

        @Override public boolean applyWeight(Object drawable, Field paintField, int weight) {
            return false;
        }

        @Override public boolean restoreWeight(
                Object drawable, Field paintField, Object fontBaseline) {
            return false;
        }

        @Override public boolean applyTextSize(
                Object drawable, Field paintField, int tenthsDp, float density) {
            return false;
        }

        @Override public boolean restoreTextSize(
                Object drawable, Field paintField, float textSizePx) {
            return false;
        }

        @Override public boolean recomputeTextBounds(Object drawable, Method boundsUpdater) {
            return false;
        }

        @Override public void invalidate(Object drawable) {
        }
    }

    private static final class InitialCaptureMutator extends NoOpMutator {
        private final AtomicInteger captureCalls = new AtomicInteger();
        private final CountDownLatch firstCaptureStarted = new CountDownLatch(1);
        private final CountDownLatch releaseFirstCapture = new CountDownLatch(1);

        @Override public InitialBaseline captureInitialBaseline(
                Object drawable, Field paintField) {
            if (captureCalls.incrementAndGet() == 1) {
                firstCaptureStarted.countDown();
                await(releaseFirstCapture);
            }
            return new InitialBaseline(new Object(), 10.0f);
        }
    }

    private static final class DelayedBaselineMutator extends NoOpMutator {
        private final CountDownLatch initialCaptureStarted = new CountDownLatch(1);
        private final CountDownLatch releaseInitialCapture = new CountDownLatch(1);
        private final Object initialFontBaseline = new Object();
        private final Object newestFontBaseline = new Object();

        @Override public InitialBaseline captureInitialBaseline(
                Object drawable, Field paintField) {
            initialCaptureStarted.countDown();
            await(releaseInitialCapture);
            return new InitialBaseline(initialFontBaseline, 10.0f);
        }

        @Override public Object captureFontBaseline(
                Object drawable, Field paintField, String systemVariation) {
            return newestFontBaseline;
        }
    }

    private static final class PendingReplacementMutator extends NoOpMutator {
        private final AtomicInteger initialCaptureCalls = new AtomicInteger();
        private final CountDownLatch replacementCaptureStarted = new CountDownLatch(1);
        private final CountDownLatch releaseReplacementCapture = new CountDownLatch(1);
        private final Object initialBaseline = new Object();
        private final Object replacementBaseline = new Object();

        @Override public InitialBaseline captureInitialBaseline(
                Object drawable, Field paintField) {
            initialCaptureCalls.incrementAndGet();
            return new InitialBaseline(initialBaseline, 10.0f);
        }

        @Override public Object captureFontBaseline(
                Object drawable, Field paintField, String systemVariation) {
            replacementCaptureStarted.countDown();
            await(releaseReplacementCapture);
            return replacementBaseline;
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new AssertionError("timed out waiting for coordinated capture");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError(exception);
        }
    }
}
