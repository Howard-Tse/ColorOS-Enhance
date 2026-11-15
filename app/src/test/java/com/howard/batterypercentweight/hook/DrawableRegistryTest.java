package com.howard.batterypercentweight.hook;

import org.junit.Test;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.fail;

public final class DrawableRegistryTest {
    private static final class Fixture {
        private Object percentInPaint;

        public void updateBatteryLevel() {
        }

        public android.content.Context getContext() {
            return null;
        }
    }

    @Test public void deduplicatesByIdentityAndVisitsLiveObject() throws Exception {
        DrawableRegistry registry = new DrawableRegistry(WeakReference::new);
        Object drawable = new Object();
        Field field = Fixture.class.getDeclaredField("percentInPaint");

        registry.register(drawable, field, boundsUpdater(), contextGetter(), 3.0f);
        registry.register(drawable, field, boundsUpdater(), contextGetter(), 3.0f);

        List<Object> visited = new ArrayList<>();
        registry.forEachLive(entry -> visited.add(entry.drawable()));

        assertEquals(List.of(drawable), visited);
        assertEquals(1, registry.trackedCount());
    }

    @Test public void removesClearedReferences() throws Exception {
        AtomicReference<WeakReference<Object>> captured = new AtomicReference<>();
        DrawableRegistry registry = new DrawableRegistry(value -> {
            WeakReference<Object> reference = new WeakReference<>(value);
            captured.set(reference);
            return reference;
        });
        registry.register(
                new Object(),
                Fixture.class.getDeclaredField("percentInPaint"),
                boundsUpdater(),
                contextGetter(),
                3.0f);

        captured.get().clear();
        registry.forEachLive(entry -> fail("cleared reference was visited"));

        assertEquals(0, registry.trackedCount());
    }

    @Test public void concurrentRegistrationsOfSameObjectCreateOneEntry() throws Exception {
        CyclicBarrier factoriesReady = new CyclicBarrier(2);
        DrawableRegistry registry = new DrawableRegistry(value -> {
            await(factoriesReady);
            return new WeakReference<>(value);
        });
        Object drawable = new Object();
        Field field = Fixture.class.getDeclaredField("percentInPaint");
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            Future<?> first = executor.submit(() -> registry.register(
                    drawable, field, uncheckedBoundsUpdater(), uncheckedContextGetter(), 3.0f));
            Future<?> second = executor.submit(() -> registry.register(
                    drawable, field, uncheckedBoundsUpdater(), uncheckedContextGetter(), 3.0f));

            first.get(5, TimeUnit.SECONDS);
            second.get(5, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }

        assertEquals(1, registry.trackedCount());
    }

    @Test public void keepsDrawableStronglyReachableForEntireConsumerCallback() throws Exception {
        Object drawable = new Object();
        DrawableRegistry registry = new DrawableRegistry(value -> new WeakReference<>(value) {
            private int gets;

            @Override public Object get() {
                return gets++ == 0 ? drawable : null;
            }
        });
        registry.register(
                drawable,
                Fixture.class.getDeclaredField("percentInPaint"),
                boundsUpdater(),
                contextGetter(),
                3.0f);
        List<Object> visited = new ArrayList<>();

        registry.forEachLive(entry -> {
            visited.add(entry.drawable());
            visited.add(entry.drawable());
        });

        assertEquals(List.of(drawable, drawable), visited);
    }

    @Test public void retainsResolvedMetadataAndFindsByIdentity() throws Exception {
        DrawableRegistry registry = new DrawableRegistry();
        Fixture drawable = new Fixture();
        Field field = Fixture.class.getDeclaredField("percentInPaint");
        Method boundsUpdater = boundsUpdater();

        DrawableRegistry.Entry entry = registry.register(
                drawable, field, boundsUpdater, contextGetter(), 2.75f);

        assertSame(entry, registry.find(drawable));
        assertSame(field, entry.field());
        assertSame(boundsUpdater, entry.boundsUpdater());
        assertEquals(2.75f, entry.density(), 0.0f);
    }

    private static Method boundsUpdater() throws NoSuchMethodException {
        return Fixture.class.getDeclaredMethod("updateBatteryLevel");
    }

    private static Method contextGetter() throws NoSuchMethodException {
        return Fixture.class.getDeclaredMethod("getContext");
    }

    private static Method uncheckedBoundsUpdater() {
        try {
            return boundsUpdater();
        } catch (NoSuchMethodException exception) {
            throw new AssertionError(exception);
        }
    }

    private static Method uncheckedContextGetter() {
        try {
            return contextGetter();
        } catch (NoSuchMethodException exception) {
            throw new AssertionError(exception);
        }
    }

    private static void await(CyclicBarrier barrier) {
        try {
            barrier.await(5, TimeUnit.SECONDS);
        } catch (Exception exception) {
            throw new AssertionError(exception);
        }
    }
}
