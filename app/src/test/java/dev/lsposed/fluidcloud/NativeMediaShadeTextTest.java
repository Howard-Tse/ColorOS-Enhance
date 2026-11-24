package dev.lsposed.fluidcloud;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Method;
import java.util.List;
import org.junit.Test;

public final class NativeMediaShadeTextTest {
    private record Flow(Object value) { }
    private static final class Section { boolean lyrics; }
    private static final class Model {
        Flow immersive = new Flow(false);
        Flow lines = new Flow(null);
    }

    @Test public void restoresLyricModeWhenUnlockMissedTheMetadataEvent() throws ReflectiveOperationException {
        Section section = new Section();
        Model vm = new Model();
        var mode = new NativeMediaShadeText.LyricMode(Reflect.field(Section.class, "lyrics", boolean.class),
                Reflect.field(Model.class, "immersive", Flow.class), Reflect.field(Model.class, "lines", Flow.class));
        Method value = Reflect.method(Flow.class, "value", Object.class);
        assertFalse(mode.needsMetadata(section, vm, value));
        vm.lines = new Flow(List.of());
        assertFalse(mode.needsMetadata(section, vm, value));
        vm.lines = new Flow(List.of("line"));
        vm.immersive = new Flow(true);
        assertFalse(mode.needsMetadata(section, vm, value));
        vm.immersive = new Flow(false);
        assertTrue(mode.needsMetadata(section, vm, value));
        section.lyrics = true;
        assertFalse(mode.needsMetadata(section, vm, value));
    }

    @Test public void fillsOnlyUpdatesSkippedByNativeUnlockedCollector() {
        assertTrue(NativeMediaShadeText.needsReplay(false, false, false, true));
        assertFalse(NativeMediaShadeText.needsReplay(true, false, false, true));
        assertFalse(NativeMediaShadeText.needsReplay(false, true, false, true));
    }

    @Test public void keepsHiddenMetadataCurrentWithoutRunningLyricTicksOffscreen() {
        assertTrue(NativeMediaShadeText.needsReplay(false, false, false, false));
        assertFalse(NativeMediaShadeText.needsReplay(false, false, true, false));
        assertTrue(NativeMediaShadeText.needsReplay(false, false, true, true));
    }
}
