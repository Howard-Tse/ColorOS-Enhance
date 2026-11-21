package dev.lsposed.fluidcloud;

import android.content.Context;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

public class MediaWaveSourceTest {
    private static Class<?> legacyTypes(String name) throws ClassNotFoundException {
        return switch (name) {
            case "com.oplus.systemui.seedlingservice.mediaControl.SeedlingMediaData" -> Media.class;
            case "com.oplus.systemui.media.seedling.MediaSeedling" -> Seedling.class;
            case "com.oplus.systemui.media.seedling.OplusMediaSeedlingController" -> SeedlingController.class;
            case "dagger.Lazy" -> Lazy.class;
            default -> throw new ClassNotFoundException(name);
        };
    }

    @Test public void legacySenderOnlySelectsUnambiguousSession() throws Exception {
        var source = new MediaWaveSource(MediaWaveSourceTest::legacyTypes);
        assertEquals(Seedling.class.getDeclaredMethod("updateSeedlings", boolean.class, boolean.class,
                boolean.class), source.legacyUpdate);
        var controller = new SeedlingController();
        var sender = new Seedling(() -> controller);
        var music = new Media("music");
        assertNull(source.selected(sender));
        controller.entries = List.of(music);
        assertSame(music, source.selected(sender));
        controller.entries = List.of(music, new Media("video"));
        assertNull(source.selected(sender));
        assertNull(source.selected(new Seedling(() -> new Object())));
    }

    @Test public void liveAlertSenderKeepsMatchingNativePlayerIdentity() throws Exception {
        var source = new MediaWaveSource(name -> name.endsWith(".MediaLiveAlertController")
                ? LiveAlertController.class : legacyTypes(name));
        var sender = new LiveAlertController();
        var music = new Media("music");
        var video = new Media("video");
        sender.mediaDataList = List.of(music, video);
        sender.lastStatusBarPlayerId = "video";
        assertNull(source.legacyUpdate);
        assertSame(video, source.selected(sender));
        sender.lastStatusBarPlayerId = "other";
        assertNull(source.selected(sender));
        sender.mediaDataList = List.of(music);
        assertSame(music, source.selected(sender));
    }

    record Media(String id) {
        public String getPlayerId() { return id; }
    }

    interface Lazy { Object get(); }

    static final class SeedlingController {
        List<Media> entries = List.of();
        public List<Media> getSortedEntries() { return entries; }
    }

    static final class Seedling {
        Context context;
        final Lazy oplusMediaSeedlingController;
        Seedling(Lazy controller) { oplusMediaSeedlingController = controller; }
        public void updateSeedlings(boolean create, boolean milestone, boolean updateMedia) { }
    }

    static final class LiveAlertController {
        Context sysContext;
        List<Media> mediaDataList;
        String lastStatusBarPlayerId;
    }
}
