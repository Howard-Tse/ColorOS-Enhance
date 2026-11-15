package com.howard.batterypercentweight.settings;

import org.junit.Test;

import com.howard.batterypercentweight.PreferenceKeys;
import com.howard.batterypercentweight.TypographySettings;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class RemoteSettingsStoreTest {
    @Test public void readUsesDefaultsAndNormalizesStoredWeight() {
        FakePort port = new FakePort();
        port.booleans.put(PreferenceKeys.ENABLED, false);
        port.integers.put(PreferenceKeys.WEIGHT, 566);

        TypographySettings settings = new RemoteSettingsStore(port).read();

        assertFalse(settings.enabled());
        assertFalse(settings.weightEnabled());
        assertEquals(570, settings.weight());
        assertFalse(settings.textSizeEnabled());
        assertFalse(settings.horizontalOffsetEnabled());
        assertFalse(settings.verticalOffsetEnabled());
    }

    @Test public void readUsesNewModuleDefaultsWhenNoValuesAreStored() {
        FakePort port = new FakePort();

        TypographySettings settings = new RemoteSettingsStore(port).read();

        assertEquals(TypographySettings.defaults(), settings);
        assertFalse(settings.weightEnabled());
        assertFalse(settings.textSizeEnabled());
        assertFalse(settings.horizontalOffsetEnabled());
        assertFalse(settings.verticalOffsetEnabled());
    }

    @Test public void writeCommitsEveryNormalizedField() {
        FakePort port = new FakePort();
        TypographySettings settings = new TypographySettings(
                false, false, 1007, true, 141, true, -31, true, 31);

        assertTrue(new RemoteSettingsStore(port).commit(
                settings, TypographySettings.defaults()));

        assertFalse(port.booleanValue(PreferenceKeys.ENABLED));
        assertFalse(port.booleanValue(PreferenceKeys.WEIGHT_ENABLED));
        assertEquals(1000, port.integerValue(PreferenceKeys.WEIGHT));
        assertTrue(port.booleanValue(PreferenceKeys.TEXT_SIZE_ENABLED));
        assertEquals(140, port.integerValue(PreferenceKeys.TEXT_SIZE_TENTHS_DP));
        assertTrue(port.booleanValue(PreferenceKeys.HORIZONTAL_OFFSET_ENABLED));
        assertEquals(-30, port.integerValue(PreferenceKeys.HORIZONTAL_OFFSET_TENTHS_DP));
        assertTrue(port.booleanValue(PreferenceKeys.VERTICAL_OFFSET_ENABLED));
        assertEquals(30, port.integerValue(PreferenceKeys.VERTICAL_OFFSET_TENTHS_DP));
    }

    @Test public void rejectedWriteRestoresFullCommittedRecordInLocalCache() {
        FakePort port = new FakePort();
        TypographySettings committed = new TypographySettings(
                true, true, 570, false, 100, false, 0, false, 0);
        TypographySettings requested = new TypographySettings(
                false, false, 900, true, 120, true, -20, true, 30);
        port.replaceLocalRecord(committed);
        port.writeOutcomes.add(false);
        port.writeOutcomes.add(false);

        boolean saved = new RemoteSettingsStore(port).commit(requested, committed);

        assertFalse(saved);
        assertEquals(2, port.writeCalls);
        assertEquals(committed, new RemoteSettingsStore(port).read());
    }

    @Test public void throwingWriteAndCompensationStillReportFailureWithoutEscaping() {
        FakePort port = new FakePort();
        TypographySettings committed = TypographySettings.defaults();
        TypographySettings requested = new TypographySettings(
                false, false, 800, true, 130, true, 20, true, -20);
        port.replaceLocalRecord(committed);
        port.writeOutcomes.add(new IllegalStateException("request failed"));
        port.writeOutcomes.add(new IllegalStateException("compensation failed"));

        boolean saved = new RemoteSettingsStore(port).commit(requested, committed);

        assertFalse(saved);
        assertEquals(2, port.writeCalls);
        assertEquals(committed, new RemoteSettingsStore(port).read());
    }

    private static final class FakePort implements PreferencePort {
        private final Map<String, Boolean> booleans = new HashMap<>();
        private final Map<String, Integer> integers = new HashMap<>();
        private final List<Object> writeOutcomes = new ArrayList<>();
        private int writeCalls;

        @Override public boolean getBoolean(String key, boolean fallback) {
            Boolean value = booleans.get(key);
            return value == null ? fallback : value;
        }

        @Override public int getInt(String key, int fallback) {
            Integer value = integers.get(key);
            return value == null ? fallback : value;
        }

        @Override public boolean write(TypographySettings settings) {
            writeCalls++;
            replaceLocalRecord(settings);
            if (!writeOutcomes.isEmpty()) {
                Object outcome = writeOutcomes.remove(0);
                if (outcome instanceof RuntimeException runtimeException) {
                    throw runtimeException;
                }
                return (Boolean) outcome;
            }
            return true;
        }

        private void replaceLocalRecord(TypographySettings settings) {
            booleans.put(PreferenceKeys.ENABLED, settings.enabled());
            booleans.put(PreferenceKeys.WEIGHT_ENABLED, settings.weightEnabled());
            integers.put(PreferenceKeys.WEIGHT, settings.weight());
            booleans.put(PreferenceKeys.TEXT_SIZE_ENABLED, settings.textSizeEnabled());
            integers.put(PreferenceKeys.TEXT_SIZE_TENTHS_DP, settings.textSizeTenthsDp());
            booleans.put(PreferenceKeys.HORIZONTAL_OFFSET_ENABLED,
                    settings.horizontalOffsetEnabled());
            integers.put(PreferenceKeys.HORIZONTAL_OFFSET_TENTHS_DP,
                    settings.horizontalOffsetTenthsDp());
            booleans.put(PreferenceKeys.VERTICAL_OFFSET_ENABLED,
                    settings.verticalOffsetEnabled());
            integers.put(PreferenceKeys.VERTICAL_OFFSET_TENTHS_DP,
                    settings.verticalOffsetTenthsDp());
        }

        boolean booleanValue(String key) {
            return booleans.get(key);
        }

        int integerValue(String key) {
            return integers.get(key);
        }
    }
}
