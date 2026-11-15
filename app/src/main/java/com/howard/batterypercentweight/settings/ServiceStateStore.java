package com.howard.batterypercentweight.settings;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

public final class ServiceStateStore<T> {
    private final Executor callbackExecutor;
    private final Map<Consumer<T>, Long> observers = new LinkedHashMap<>();
    private final Deque<Notification<T>> notifications = new ArrayDeque<>();

    private T current;
    private long nextGeneration;
    private boolean drainScheduled;

    public ServiceStateStore(Executor callbackExecutor) {
        this.callbackExecutor = Objects.requireNonNull(callbackExecutor);
    }

    public void observe(Consumer<T> observer, boolean deliverCurrent) {
        Objects.requireNonNull(observer);
        boolean scheduleDrain = false;
        synchronized (this) {
            Long generation = observers.get(observer);
            if (generation == null) {
                generation = ++nextGeneration;
                observers.put(observer, generation);
            }
            if (deliverCurrent) {
                notifications.addLast(new Notification<>(observer, generation, current));
                scheduleDrain = markDrainScheduled();
            }
        }
        scheduleDrain(scheduleDrain);
    }

    public void remove(Consumer<T> observer) {
        synchronized (this) {
            observers.remove(observer);
        }
    }

    public void update(T state) {
        boolean scheduleDrain;
        synchronized (this) {
            current = state;
            enqueueNotifications(state);
            scheduleDrain = markDrainScheduled();
        }
        scheduleDrain(scheduleDrain);
    }

    public boolean clearIfCurrent(T state) {
        boolean scheduleDrain;
        synchronized (this) {
            if (current != state) {
                return false;
            }
            current = null;
            enqueueNotifications(null);
            scheduleDrain = markDrainScheduled();
        }
        scheduleDrain(scheduleDrain);
        return true;
    }

    private void enqueueNotifications(T state) {
        for (Map.Entry<Consumer<T>, Long> observer : observers.entrySet()) {
            notifications.addLast(new Notification<>(
                    observer.getKey(), observer.getValue(), state));
        }
    }

    private boolean markDrainScheduled() {
        if (drainScheduled || notifications.isEmpty()) {
            return false;
        }
        drainScheduled = true;
        return true;
    }

    private void scheduleDrain(boolean scheduleDrain) {
        if (!scheduleDrain) {
            return;
        }
        try {
            callbackExecutor.execute(this::drain);
        } catch (RuntimeException ignored) {
            synchronized (this) {
                notifications.clear();
                drainScheduled = false;
            }
        }
    }

    private void drain() {
        while (true) {
            Notification<T> notification;
            boolean currentRegistration;
            synchronized (this) {
                notification = notifications.pollFirst();
                if (notification == null) {
                    drainScheduled = false;
                    return;
                }
                Long generation = observers.get(notification.observer());
                currentRegistration = generation != null
                        && generation.longValue() == notification.generation();
            }
            if (!currentRegistration) {
                continue;
            }
            try {
                notification.observer().accept(notification.state());
            } catch (RuntimeException ignored) {
                // One observer must not block delivery to the remaining observers.
            }
        }
    }

    private record Notification<T>(Consumer<T> observer, long generation, T state) {
    }
}
