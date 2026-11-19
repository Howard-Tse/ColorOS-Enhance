package dev.howard.colorosenhance.diagnostics;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 36, manifest = Config.NONE)
public final class DiagnosticStoreTest {
    @Test public void boundsHistoryByAgeAndCountWithoutDiscardingLastFeatureStatus() {
        DiagnosticStore store = DiagnosticStore.get(RuntimeEnvironment.getApplication());
        long now = 2 * DiagnosticStore.RETENTION_MS;
        store.record(List.of(new DiagnosticStore.Signal(DiagnosticPoint.SETTINGS, DiagnosticPoint.State.INSTALLED,
                now - DiagnosticStore.RETENTION_MS - 1)), 2, now);
        List<DiagnosticStore.Signal> batch = new ArrayList<>();
        for (int i = 527; i >= 0; i--) batch.add(new DiagnosticStore.Signal(DiagnosticPoint.MEDIA_WAVE_CAPTURE,
                DiagnosticPoint.State.ACTIVE, now - i));
        store.record(batch, 3, now);

        var events = store.recent(now, DiagnosticStore.RETENTION_MS);
        assertEquals(DiagnosticStore.LIMIT, events.size());
        assertEquals(now - DiagnosticStore.LIMIT + 1, events.getFirst().time());
        assertEquals(now, events.getLast().time());
        assertEquals(3, store.recent(now, 2).size());
        assertTrue(store.latest().containsKey(DiagnosticPoint.SETTINGS));
        assertEquals(3, store.latest().get(DiagnosticPoint.MEDIA_WAVE_CAPTURE).boot());
    }
}
