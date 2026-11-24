package dev.lsposed.fluidcloud;

import static org.junit.Assert.*;

import android.os.Bundle;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 36, manifest = Config.NONE)
public final class NativeMediaShadeStyleTest {
    private record Change(Object row, int from, int to) { }
    private final List<Change> rendered = new ArrayList<>();
    private final NativeMediaShadeStyle.Presentation shade =
            new NativeMediaShadeStyle.Presentation((row, from, to) -> rendered.add(new Change(row, from, to)));

    private static Bundle nativeChange(int from, int to) {
        Bundle event = new Bundle();
        event.putInt("fromState", from);
        event.putInt("toState", to);
        event.putBoolean("animate", true);
        event.putInt("reason", 101);
        return event;
    }

    @Test public void openingShadeFromImmersiveUsesStackAndRestoresOnExit() {
        Object media = new Object();
        shade.show(media, 2);
        shade.show(media, 0); // Another native row refresh for the same song or after a seek.
        assertFalse(shade.owns(new Object()));
        shade.release();
        assertEquals(List.of(new Change(media, 2, 0), new Change(media, 0, 2)), rendered);
    }

    @Test public void pendingLockscreenChoiceIsSavedWithoutChangingTheSystemEvent() {
        Object media = new Object();
        shade.show(media, 0);
        Bundle nativeRequest = nativeChange(0, 2);
        Bundle shown = shade.event(nativeRequest, true);
        assertNotSame(nativeRequest, shown);
        assertEquals(0, shown.getInt("toState"));
        assertEquals(0, shown.getInt("fromState"));
        assertFalse(shown.getBoolean("animate"));
        assertEquals(101, shown.getInt("reason"));
        assertEquals(2, nativeRequest.getInt("toState"));
        assertTrue(nativeRequest.getBoolean("animate"));
        shade.release();
        assertEquals(new Change(media, 0, 2), rendered.getLast());
    }

    @Test public void nativeKeyguardTransitionWinsIfItArrivesBeforeTheHostStateCallback() {
        Object media = new Object();
        shade.show(media, 2);
        Bundle lock = nativeChange(0, 1);
        assertSame(lock, shade.event(lock, false));
        assertFalse(shade.owns(media));
        shade.release();
        assertEquals(List.of(new Change(media, 2, 0)), rendered);
    }

    @Test public void replacingTheMediaViewRestoresOnlyItsOwnTarget() {
        Object first = new Object(), next = new Object();
        shade.show(first, 2);
        shade.show(next, 1);
        Bundle ordinary = nativeChange(0, 0);
        assertSame(ordinary, shade.event(ordinary, true));
        shade.release();
        assertEquals(List.of(new Change(first, 2, 0), new Change(first, 0, 2),
                new Change(next, 1, 0)), rendered);
    }
}
