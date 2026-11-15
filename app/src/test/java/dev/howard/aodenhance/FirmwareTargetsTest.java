package dev.howard.aodenhance;

import java.util.Map;
import org.junit.Test;
import static org.junit.Assert.*;

public class FirmwareTargetsTest {
    @Test public void independentDimmingRequiresBothModernContractsAndKeeps16Legacy() {
        var modern=Map.of("com.oplus.keyguard.ui.OplusKeyguardStyleClock",NewClock.class,
                "com.oplus.systemui.keyguard.ui.view.section.OplusKgdMaskSection",NewPlugin.class);
        assertTrue(targets(modern).separateBackgroundDim());
        assertFalse(targets(Map.of()).separateBackgroundDim());
        assertFalse(targets(Map.of("com.oplus.keyguard.ui.OplusKeyguardStyleClock",NewClock.class)).separateBackgroundDim());
    }
    private static final String OLD_GESTURE = "com.oplus.systemui.aod.scene.PanoramicAodSingleClickWakeUpController$PanoramicAodSingleClickWakeUpCallback";
    private static final String NEW_GESTURE = "com.oplus.systemui.aod.scene.PanoramicAodGestureController$PanoramicAodGestureCallback";

    @Test public void resolvesRenamedGestureCallbackOn17() throws Exception {
        assertSame(NewGesture.class, targets(Map.of(NEW_GESTURE, NewGesture.class)).gestureCallback());
    }

    @Test public void retainsGestureCallbackOn16() throws Exception {
        assertSame(OldGesture.class, targets(Map.of(OLD_GESTURE, OldGesture.class)).gestureCallback());
    }

    @Test public void newClockUsesSetupBecauseItInheritsAttachLifecycle() throws Exception {
        NewPlugin plugin = new NewPlugin();
        var target = targets(Map.of("com.oplus.keyguard.ui.OplusKeyguardStyleClock", NewClock.class,
                "com.oplus.keyguard.plugin.KeyguardPlugin", NewPlugin.class)).clockRoot();
        target.method().invoke(plugin, 0, 14);
        assertEquals(14, plugin.setupScope);
        assertSame(plugin.pluginContainer, target.root(plugin));
    }

    @Test public void oldClockStillRegistersAfterAttach() throws Exception {
        OldClock clock = new OldClock();
        var target = targets(Map.of("com.oplus.keyguard.OplusKeyguardStyleClock", OldClock.class)).clockRoot();
        target.method().invoke(clock);
        assertTrue(clock.attached);
        assertSame(clock, target.root(clock));
    }

    @Test public void newWidgetUsesSubPluginRootZeroAndRejectsClockViews() throws Exception {
        var target = targets(Map.of("com.oplus.keyguard.plugin.AbstractSubPlugin", NewSubPlugin.class)).pluginViews();
        assertEquals("root", target.method().invoke(new NewSubPlugin(), 0));
        assertTrue(target.isWidgetRoot("com.oplus.keyguard.plugin.WidgetPlugin", 0));
        assertFalse(target.isWidgetRoot("com.oplus.keyguard.plugin.WidgetPlugin", 16));
        assertFalse(target.isWidgetRoot("com.oplus.keyguard.plugin.ClockPlugin", 0));
    }

    @Test public void oldWidgetRetainsRootSixteen() throws Exception {
        var target = targets(Map.of("com.oplus.keyguard.plugin.KeyguardPlugin", OldPlugin.class)).pluginViews();
        assertEquals("root", target.method().invoke(new OldPlugin(), 16));
        assertTrue(target.isWidgetRoot("com.oplus.keyguard.plugin.WidgetPlugin", 16));
        assertFalse(target.isWidgetRoot("com.oplus.keyguard.plugin.WidgetPlugin", 0));
        assertFalse(target.isWidgetRoot("com.oplus.keyguard.plugin.ThemePlugin", 16));
    }

    private FirmwareTargets targets(Map<String, Class<?>> available) {
        return new FirmwareTargets(name -> {
            Class<?> type = available.get(name);
            if (type == null) throw new ClassNotFoundException(name);
            return type;
        });
    }
    public static class OldGesture {}
    public static class NewGesture {}
    public static class OldClock {
        boolean attached;
        public void onAttachedToWindow() { attached = true; }
    }
    public static class NewClock {
        int setupScope;
        public void setUp(int scope) { setupScope = scope; }
    }
    public static class NewPlugin {
        private final Object pluginContainer = new Object();
        int setupScope;
        public void setUp(int userId, int scope) { setupScope = scope; }
    }
    public static class OldPlugin {
        public Object getView(int kind) { return kind == 16 ? "root" : null; }
    }
    public static class NewSubPlugin {
        public Object getView(int kind) { return kind == 0 ? "root" : null; }
    }
}
