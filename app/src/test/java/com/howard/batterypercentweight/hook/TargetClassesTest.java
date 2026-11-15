package com.howard.batterypercentweight.hook;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.graphics.Paint;

import org.junit.Test;

import java.util.List;
import java.util.Map;

public final class TargetClassesTest {
    private static final class Supported {
        private Paint batteryPercentPaint;
    }

    private static final class Ambiguous {
        private Paint percentPrimaryPaint;
        private Paint percentSecondaryPaint;
    }

    @Test
    public void resolvesOnlyLoadableCandidatesWithOnePercentagePaint() {
        Map<String, Class<?>> available = Map.of(
                TargetClasses.CANDIDATES.get(0), Ambiguous.class,
                TargetClasses.CANDIDATES.get(1), Supported.class);

        List<TargetClasses.ResolvedTarget> result = TargetClasses.resolve(name -> {
            Class<?> type = available.get(name);
            if (type == null) {
                throw new ClassNotFoundException(name);
            }
            return type;
        });

        assertEquals(1, result.size());
        assertSame(Supported.class, result.get(0).type());
        assertEquals("batteryPercentPaint", result.get(0).paintField().getName());
    }

    @Test
    public void skipsCandidateWhenClassLookupIsRejected() {
        List<TargetClasses.ResolvedTarget> result = TargetClasses.resolve(name -> {
            if (TargetClasses.CANDIDATES.get(0).equals(name)) {
                throw new SecurityException("blocked");
            }
            throw new ClassNotFoundException(name);
        });

        assertTrue(result.isEmpty());
    }
}
