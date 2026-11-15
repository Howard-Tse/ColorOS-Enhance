package com.howard.batterypercentweight.hook;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

public final class HookTransactionTest {
    @Test public void commitReturnsHandlesWithoutRollingThemBack() {
        List<String> rolledBack = new ArrayList<>();
        HookTransaction<String> transaction =
                new HookTransaction<>(rolledBack::add, ignored -> { });
        transaction.record("constructor");
        transaction.record("setter");

        List<String> retained = transaction.commit();

        assertEquals(List.of("constructor", "setter"), retained);
        assertEquals(List.of(), rolledBack);
    }

    @Test public void rollbackUnhooksEveryHandleInReverseOrder() {
        List<String> rolledBack = new ArrayList<>();
        List<Throwable> failures = new ArrayList<>();
        HookTransaction<String> transaction =
                new HookTransaction<>(rolledBack::add, failures::add);
        transaction.record("first");
        transaction.record("second");
        transaction.record("third");

        transaction.rollback();

        assertEquals(List.of("third", "second", "first"), rolledBack);
        assertEquals(List.of(), failures);
    }

    @Test public void rollbackFailureDoesNotPreventRemainingHandles() {
        List<String> rolledBack = new ArrayList<>();
        List<Throwable> failures = new ArrayList<>();
        RuntimeException failure = new RuntimeException("unhook failed");
        HookTransaction<String> transaction = new HookTransaction<>(handle -> {
            rolledBack.add(handle);
            if ("second".equals(handle)) {
                throw failure;
            }
        }, failures::add);
        transaction.record("first");
        transaction.record("second");
        transaction.record("third");

        transaction.rollback();

        assertEquals(List.of("third", "second", "first"), rolledBack);
        assertEquals(1, failures.size());
        assertSame(failure, failures.get(0));
    }
}
