package com.howard.batterypercentweight.settings;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public final class ServiceStateStoreTest {
    @Test public void observerReceivesCurrentAndFutureState() {
        ServiceStateStore<Object> store = new ServiceStateStore<>(Runnable::run);
        List<Object> states = new ArrayList<>();

        store.observe(states::add, true);

        assertNull(states.get(0));
        Object service = new Object();
        store.update(service);
        assertSame(service, states.get(1));
    }

    @Test public void removedObserverStopsReceivingUpdates() {
        ServiceStateStore<Object> store = new ServiceStateStore<>(Runnable::run);
        Consumer<Object> observer = ignored -> fail("removed observer invoked");

        store.observe(observer, false);
        store.remove(observer);
        store.update(new Object());
    }

    @Test public void observingTheSameObserverTwiceDoesNotDuplicateUpdates() {
        ServiceStateStore<Object> store = new ServiceStateStore<>(Runnable::run);
        List<Object> states = new ArrayList<>();
        Consumer<Object> observer = states::add;

        store.observe(observer, false);
        store.observe(observer, false);
        Object service = new Object();
        store.update(service);

        assertEquals(List.of(service), states);
    }

    @Test public void observerCanRemoveItselfDuringAnUpdate() {
        ServiceStateStore<Object> store = new ServiceStateStore<>(Runnable::run);
        List<Object> states = new ArrayList<>();
        AtomicReference<Consumer<Object>> observer = new AtomicReference<>();
        observer.set(state -> {
            states.add(state);
            store.remove(observer.get());
        });
        store.observe(observer.get(), false);

        store.update(new Object());
        store.update(new Object());

        assertEquals(1, states.size());
    }

    @Test public void notificationsAreDispatchedByTheProvidedExecutor() {
        List<Runnable> queued = new ArrayList<>();
        ServiceStateStore<Object> store = new ServiceStateStore<>(queued::add);
        List<Object> states = new ArrayList<>();

        store.observe(states::add, true);

        assertTrue(states.isEmpty());
        queued.remove(0).run();
        assertNull(states.get(0));
    }

    @Test public void removedAndReaddedObserverDoesNotReceiveStaleQueuedNotifications() {
        List<Runnable> queued = new ArrayList<>();
        ServiceStateStore<Object> store = new ServiceStateStore<>(queued::add);
        List<Object> states = new ArrayList<>();
        Consumer<Object> observer = states::add;
        Object oldService = new Object();
        Object newService = new Object();

        store.observe(observer, true);
        store.update(oldService);
        store.remove(observer);
        store.observe(observer, false);
        runAll(queued);

        assertTrue(states.isEmpty());
        store.update(newService);
        runAll(queued);
        assertEquals(List.of(newService), states);
    }

    @Test public void reentrantUpdateKeepsEveryObserverInStateChangeOrder() {
        ServiceStateStore<String> store = new ServiceStateStore<>(Runnable::run);
        List<String> secondObserverStates = new ArrayList<>();
        store.observe(state -> {
            if ("old".equals(state)) {
                store.update("new");
            }
        }, false);
        store.observe(secondObserverStates::add, false);

        store.update("old");

        assertEquals(List.of("old", "new"), secondObserverStates);
    }

    @Test public void concurrentUpdateDoesNotOvertakeTheStateWhoseDrainIsInProgress()
            throws InterruptedException {
        ServiceStateStore<String> store = new ServiceStateStore<>(Runnable::run);
        CountDownLatch firstObserverEntered = new CountDownLatch(1);
        CountDownLatch allowFirstObserver = new CountDownLatch(1);
        List<String> secondObserverStates = new ArrayList<>();
        store.observe(state -> {
            if ("first".equals(state)) {
                firstObserverEntered.countDown();
                await(allowFirstObserver);
            }
        }, false);
        store.observe(secondObserverStates::add, false);

        Thread firstUpdate = new Thread(() -> store.update("first"));
        firstUpdate.start();
        firstObserverEntered.await();
        Thread secondUpdate = new Thread(() -> store.update("second"));
        secondUpdate.start();
        secondUpdate.join();
        allowFirstObserver.countDown();
        firstUpdate.join();

        assertEquals(List.of("first", "second"), secondObserverStates);
    }

    @Test public void throwingObserverDoesNotBlockOtherObserversOrEscapeUpdate() {
        ServiceStateStore<Object> store = new ServiceStateStore<>(Runnable::run);
        List<Object> states = new ArrayList<>();
        Object service = new Object();
        store.observe(ignored -> {
            throw new RuntimeException("observer failure");
        }, false);
        store.observe(states::add, false);

        store.update(service);

        assertEquals(List.of(service), states);
    }

    @Test public void schedulingFailureDoesNotEscapeAndLaterUpdatesCanBeDelivered() {
        AtomicBoolean rejectNext = new AtomicBoolean(true);
        ServiceStateStore<String> store = new ServiceStateStore<>(action -> {
            if (rejectNext.getAndSet(false)) {
                throw new RejectedExecutionException("executor rejected drain");
            }
            action.run();
        });
        List<String> states = new ArrayList<>();
        store.observe(states::add, false);

        store.update("dropped");
        store.update("delivered");

        assertEquals(List.of("delivered"), states);
    }

    @Test public void clearIfCurrentUsesServiceIdentity() {
        ServiceStateStore<Object> store = new ServiceStateStore<>(Runnable::run);
        List<Object> states = new ArrayList<>();
        Object first = new Object();
        Object current = new Object();
        store.observe(states::add, false);
        store.update(first);
        store.update(current);

        org.junit.Assert.assertFalse(store.clearIfCurrent(first));
        assertSame(current, states.get(1));
        assertTrue(store.clearIfCurrent(current));
        assertNull(states.get(2));
    }

    private static void runAll(List<Runnable> queued) {
        while (!queued.isEmpty()) {
            queued.remove(0).run();
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new AssertionError(error);
        }
    }
}
