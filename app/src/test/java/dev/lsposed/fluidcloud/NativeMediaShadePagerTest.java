package dev.lsposed.fluidcloud;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

public final class NativeMediaShadePagerTest {
    @Test public void firstUnlockedSwipeCanPublishSelectionWithoutVisitingKeyguard() {
        assertFalse(NativeMediaShadePager.userSelecting(false, 0));
        assertTrue(NativeMediaShadePager.userSelecting(false, 1));
        assertTrue(NativeMediaShadePager.userSelecting(false, 2));
        assertFalse(NativeMediaShadePager.userSelecting(true, 2));
    }

    @Test public void slowListCommitUsesNewSelectionAfterFirstUnlockedSwipe() {
        assertEquals(0, NativeMediaShadePager.commitTarget(0, 2, 0, 0));
        assertEquals(-1, NativeMediaShadePager.commitTarget(1, 2, 0, 0));
        assertEquals(-1, NativeMediaShadePager.commitTarget(2, 2, 0, 1));
        assertEquals(1, NativeMediaShadePager.commitTarget(0, 2, 1, 1));
    }

    @Test public void remountedPagerAcceptsNativeSelectionEvenIfItsViewStartsAtZero() {
        assertEquals(1, NativeMediaShadePager.commitTarget(0, 2, 1, 0));
        assertEquals(0, NativeMediaShadePager.commitTarget(0, 2, 0, 1));
    }

    @Test public void removedSessionAndEmptyListCannotSelectANonexistentPage() {
        assertEquals(-1, NativeMediaShadePager.commitTarget(0, 0, 1, 1));
        assertEquals(0, NativeMediaShadePager.commitTarget(0, 1, 1, 1));
        assertEquals(1, NativeMediaShadePager.commitTarget(0, 3, -1, 1));
    }
}
