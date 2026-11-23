package dev.lsposed.fluidcloud;

import org.junit.Test;
import static org.junit.Assert.*;

public class NotificationCollectionSwipeClipTest {
    record Clip(int left, int top, int right, int bottom, float topCornerRadius,
                float bottomCornerRadius, boolean useSmoothRadius) {
        public Clip copy(int l, int t, int r, int b, float rt, float rb, boolean smooth) {
            return new Clip(l, t, r, b, rt, rb, smooth);
        }
    }

    @Test public void swipingEitherWayPreservesStockInsetsAndCornerRadii() throws Exception {
        var adapter = new NativeNotificationCollectionEntry.SwipeClip(Clip.class);
        var rest = new Clip(7, 9, 401, 108, 30f, 24f, true);
        for (int offset : new int[]{-165, -1, 1, 165}) {
            Clip translated = rest.copy(7 + Math.max(0, offset), 9, 401 + Math.min(0, offset),
                    108, 30f, 24f, true);
            assertEquals(rest, adapter.local(translated, offset));
        }
        assertSame(rest, adapter.local(rest, 0));
    }
}
