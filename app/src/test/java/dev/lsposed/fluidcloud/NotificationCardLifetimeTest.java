package dev.lsposed.fluidcloud;

import java.lang.reflect.Method;
import java.util.concurrent.CompletableFuture;
import org.junit.Test;
import static org.junit.Assert.*;

public class NotificationCardLifetimeTest {
    static final class Entry {
        final Object extension = new Object();
        Object getEx() { return extension; }
    }

    @Test public void oldConvertedEntryOnlyReceivesAgeExemptionInItsOwnCheck() throws Exception {
        Method getter = Entry.class.getDeclaredMethod("getEx");
        var lifetime = new NotificationCardLifetime(getter);
        var converted = new Entry();
        var nativeCard = new Entry();
        assertFalse(lifetime.checking(converted.extension));
        assertTrue(lifetime.evaluate(converted, () -> {
            assertFalse(lifetime.checking(nativeCard.extension));
            assertFalse(CompletableFuture.supplyAsync(() -> lifetime.checking(converted.extension)).join());
            return lifetime.checking(converted.extension);
        }));
        assertFalse(lifetime.checking(converted.extension));
    }

    @Test public void policyDenialsAndNestedErrorsLeaveNoPersistentGrant() throws Exception {
        var lifetime = new NotificationCardLifetime(Entry.class.getDeclaredMethod("getEx"));
        var first = new Entry();
        var second = new Entry();
        assertFalse(lifetime.evaluate(first, () -> {
            assertThrows(IllegalStateException.class, () -> lifetime.evaluate(second, () -> {
                assertFalse(lifetime.checking(first.extension));
                throw new IllegalStateException();
            }));
            assertTrue(lifetime.checking(first.extension));
            assertFalse(lifetime.checking(second.extension));
            return false;
        }));
        assertFalse(lifetime.checking(first.extension));
    }
}
