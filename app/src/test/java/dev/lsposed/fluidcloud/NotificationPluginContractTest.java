package dev.lsposed.fluidcloud;

import static org.junit.Assert.*;

import android.view.View;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

public class NotificationPluginContractTest {
    enum Entry { STATUS, SHADE }
    static class Wrapped { }
    static class Repo {
        static Repo instance = new Repo();
        void b(Wrapped item, boolean remove) { }
        void c(Entry entry, boolean refresh) { }
    }
    static class Listener {
        final Repo c;
        final Entry entry;
        List<?> current = List.of();
        Listener(Repo repo, Entry entry) { this.c = repo; this.entry = entry; }
        void b(List<?> values) { current = values; }
    }
    static class Duplicate extends Listener {
        Duplicate(Repo repo, Entry entry) { super(repo, entry); }
    }
    static class Decoy { Decoy(List<?> first, List<?> second, int flags) { } }

    @Test public void realListenerOwnsIndependentEntryListsDespiteDecoy() throws Exception {
        for (var candidates : List.of(List.<Class<?>>of(Decoy.class, Listener.class), List.<Class<?>>of(Listener.class, Decoy.class))) {
            var binding = NotificationPluginRepository.bind(Entry.class, Wrapped.class, candidates);
            var status = (Listener) binding.listener().newInstance(binding.singleton().get(null), Entry.STATUS);
            var shade = (Listener) binding.listener().newInstance(status.c, Entry.SHADE);
            binding.publish().invoke(status, List.of("item"));
            assertSame(Repo.instance, shade.c);
            assertEquals(List.of("item"), status.current);
            assertTrue(shade.current.isEmpty());
        }
    }

    @Test public void unrelatedModelAndAmbiguousListenersFailClosed() {
        assertThrows(NoSuchMethodException.class, () -> NotificationPluginRepository.bind(Entry.class, Object.class, List.of(Listener.class)));
        assertThrows(NoSuchMethodException.class, () -> NotificationPluginRepository.bind(Entry.class, Wrapped.class, List.of(Listener.class, Duplicate.class)));
    }

    interface Action { }
    static class PreviousCapsule { ArrayList<?> g; boolean y, j; Action E; }
    static class CurrentCapsule { String g; ArrayList<?> h; boolean z, k; Object E; Action F; }

    @Test public void capsuleUsesFlagsAndHandlerFromOneLayout() throws Exception {
        var old = NativeNotificationControls.CapsuleFields.resolve(PreviousCapsule.class, Action.class);
        var next = NativeNotificationControls.CapsuleFields.resolve(CurrentCapsule.class, Action.class);
        assertEquals(List.of("g", "y", "j", "E"), List.of(old.list().getName(), old.force().getName(), old.blocked().getName(), old.action().getName()));
        assertEquals(List.of("h", "z", "k", "F"), List.of(next.list().getName(), next.force().getName(), next.blocked().getName(), next.action().getName()));
    }

    static class Ui { }
    static class PreviousCard { Object f; Ui g; Action m; boolean i; }
    static class CurrentCard { Object e; Ui f; String g; Action l; boolean h; }
    @Test public void cardStateWritesTargetTheNativeVisibleList() throws Exception {
        var old = NativeNotificationCards.CardFields.resolve(PreviousCard.class, Ui.class, Action.class);
        var next = NativeNotificationCards.CardFields.resolve(CurrentCard.class, Ui.class, Action.class);
        assertEquals(List.of("f","g","m","i"), List.of(old.current().getName(),old.state().getName(),old.listener().getName(),old.blocked().getName()));
        assertEquals(List.of("e","f","l","h"), List.of(next.current().getName(),next.state().getName(),next.listener().getName(),next.blocked().getName()));
    }

    static class PreviousRow {
        PreviousRow(String id, View view, int order, String a, String b, boolean locked, boolean clear, String c,
                    Wrapped wrapped, int flags, String tag) { }
    }
    static class CurrentRow {
        CurrentRow(String id, View view, ArrayList<?> icons, int order, String a, String b, boolean locked,
                   boolean clear, String c, Wrapped wrapped) { }
    }
    @Test public void swipeUsesDeleteMenuFlagAndWrappedModelForEachConstructor() throws Exception {
        var old = NativeShadeNotificationDismissal.RowConstructor.resolve(PreviousRow.class, Wrapped.class);
        var next = NativeShadeNotificationDismissal.RowConstructor.resolve(CurrentRow.class, Wrapped.class);
        assertEquals(6, old.deleteMenu()); assertEquals(8, old.model());
        assertEquals(7, next.deleteMenu()); assertEquals(9, next.model());
    }

    static class Material {
        static void a(android.graphics.Path path, int l, int t, int r, int b, Action profile) { }
    }
    static class InstanceOnly {
        void a(android.graphics.Path path, int l, int t, int r, int b, Action profile) { }
    }
    static class WrongSignature { static void a(boolean visible) { } }
    @Test public void materialPathMatchesStaticBoundsWriterInEitherUtility() throws Exception {
        for (String correct : List.of(".l", ".k")) {
            var path = PluginClasses.materialPath(name -> name.endsWith(correct) ? Material.class : WrongSignature.class, Action.class);
            assertEquals(Material.class, path.getDeclaringClass());
        }
        assertThrows(NoSuchMethodException.class, () -> PluginClasses.materialPath(name -> InstanceOnly.class, Action.class));
    }

    interface Policy { }
    static class Dedicated implements Policy { boolean a; }
    static class Shared implements Policy { int a; }
    static class PreviousCallback { Shared b; }
    @Test public void dedicatedFrontFilterLeavesOtherSystemPolicyBranchesAlone() throws Exception {
        var dedicated = NativeCapsuleForeground.FrontFilter.resolve(Dedicated.class, Shared.class, Policy.class);
        assertSame(Dedicated.class, dedicated.owner()); assertNull(dedicated.kind());
        var shared = NativeCapsuleForeground.FrontFilter.resolve(PreviousCallback.class, Shared.class, Policy.class);
        assertSame(Shared.class, shared.owner()); assertEquals("a", shared.kind().getName());
        assertThrows(NoSuchFieldException.class, () -> NativeCapsuleForeground.FrontFilter.resolve(Object.class, Shared.class, Policy.class));
    }
}
