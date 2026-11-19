package dev.howard.colorosenhance.notifications;

import org.junit.Test;
import static org.junit.Assert.*;

public class NotificationCloudStoreTest {
    @Test public void originalsStayVisibleUntilMatchingViewAcknowledged() {
        var store = new NotificationCloudStore<String>();
        var first = store.put("key", "test.app", "channel", "one");
        var second = store.put("key", "test.app", "channel", "two");
        assertFalse(store.ready("key", first.revision())); assertFalse(store.ready("key"));
        assertTrue(store.ready("key", second.revision())); assertTrue(store.ready("key"));
        store.failed("key"); assertFalse(store.ready("key"));
    }
    @Test public void updatesDoNotDuplicateAndReturnLastsUntilRemoval() {
        var store = new NotificationCloudStore<String>();
        var first = store.put("key", "test.app", "channel", "one"); store.ready("key", first.revision());
        store.returnOriginal("key");
        var second = store.put("key", "test.app", "channel", "two");
        assertEquals(1, store.entries().size()); assertTrue(store.suppressed("key"));
        assertFalse(store.ready("key", second.revision()));
        store.remove("key"); assertFalse(store.suppressed("key")); assertTrue(store.entries().isEmpty());
    }
    @Test public void disablingOrFailedSessionRestoresEverything() {
        var store = new NotificationCloudStore<String>();
        var first = store.put("key", "test.app", "channel", "one"); store.ready("key", first.revision());
        store.clear(); assertFalse(store.ready("key")); assertFalse(store.ready("key", first.revision()));
    }
}
