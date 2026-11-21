package dev.lsposed.fluidcloud;

import android.content.Context;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;

/** Resolves the SystemUI half of the media waveform. The plugin view is checked separately. */
final class MediaWaveSource {
    private static final String CONTROLLER = "com.oplus.systemui.media.seedling.livealert.MediaLiveAlertController";
    private static final String MEDIA_SEEDLING = "com.oplus.systemui.media.seedling.MediaSeedling";
    private static final String SEEDLING_CONTROLLER = "com.oplus.systemui.media.seedling.OplusMediaSeedlingController";
    private static final String DATA = "com.oplus.systemui.seedlingservice.mediaControl.SeedlingMediaData";

    interface Lookup { Class<?> load(String name) throws ClassNotFoundException; }

    final Class<?> data;
    final Method legacyUpdate;
    private final Field context, mediaList, lastPlayer, lazyController;
    private final Method playerId, lazyGet, sortedEntries;

    MediaWaveSource(Lookup lookup) throws ReflectiveOperationException {
        data = lookup.load(DATA);
        playerId = Reflect.method(data, "getPlayerId", String.class);
        Class<?> controller;
        try { controller = lookup.load(CONTROLLER); }
        catch (ClassNotFoundException missing) { controller = null; }
        if (controller != null) {
            context = Reflect.field(controller, "sysContext", Context.class);
            mediaList = Reflect.field(controller, "mediaDataList", List.class);
            lastPlayer = Reflect.field(controller, "lastStatusBarPlayerId", String.class);
            lazyController = null;
            lazyGet = sortedEntries = legacyUpdate = null;
        } else {
            Class<?> seedling = lookup.load(MEDIA_SEEDLING);
            Class<?> lazy = lookup.load("dagger.Lazy");
            context = Reflect.field(seedling, "context", Context.class);
            lazyController = Reflect.field(seedling, "oplusMediaSeedlingController", lazy);
            lazyGet = Reflect.method(lazy, "get", Object.class);
            sortedEntries = Reflect.method(lookup.load(SEEDLING_CONTROLLER), "getSortedEntries", List.class);
            legacyUpdate = Reflect.method(seedling, "updateSeedlings", void.class, boolean.class,
                    boolean.class, boolean.class);
            mediaList = lastPlayer = null;
        }
    }

    Context context(Object owner) { return (Context) Reflect.get(context, owner); }

    Object selected(Object owner) {
        Object entries;
        if (legacyUpdate != null) {
            Object lazy = Reflect.get(lazyController, owner);
            Object controller = lazy == null ? null : Reflect.call(lazyGet, lazy);
            if (!sortedEntries.getDeclaringClass().isInstance(controller)) return null;
            entries = Reflect.call(sortedEntries, controller);
        } else {
            entries = Reflect.get(mediaList, owner);
        }
        if (!(entries instanceof List<?> list)) return null;
        if (legacyUpdate == null) {
            String id = (String) Reflect.get(lastPlayer, owner);
            for (Object item : list) {
                if (id != null && item != null && id.equals(Reflect.call(playerId, item))) return item;
            }
        }
        // Legacy SystemUI does not report the plugin's selected player ID.
        // Leave the native animation when the sorted list cannot identify a unique player.
        return list.size() == 1 && data.isInstance(list.getFirst()) ? list.getFirst() : null;
    }
}
