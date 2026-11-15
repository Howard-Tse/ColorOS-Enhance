package com.howard.batterypercentweight.hook;

import android.graphics.Paint;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public final class PaintFieldResolverTest {
    private static final class Exact {
        private Paint unrelatedPaint;
        private Paint percentInPaint;
    }

    private static class ParentFallback {
        private Paint batteryPercentPaint;
    }

    private static final class ChildFallback extends ParentFallback {}

    private static final class Ambiguous {
        private Paint percentPaint;
        private Paint percentTextPaint;
    }

    private static final class ObjectNamedPercent {
        private Object percentInPaint;
        private Paint batteryPercentPaint;
    }

    private static final class StaticPercent {
        private static Paint percentInPaint;
    }

    private static class ParentExact {
        private Paint percentInPaint;
    }

    private static final class ChildWithAmbiguousFallback extends ParentExact {
        private Paint percentPaint;
        private Paint percentTextPaint;
    }

    @Test public void exactNameWins() {
        assertEquals("percentInPaint",
                PaintFieldResolver.resolve(Exact.class).orElseThrow().getName());
    }

    @Test public void uniquePercentPaintInHierarchyIsAccepted() {
        assertEquals("batteryPercentPaint",
                PaintFieldResolver.resolve(ChildFallback.class).orElseThrow().getName());
    }

    @Test public void ambiguousFallbackIsRejected() {
        assertTrue(PaintFieldResolver.resolve(Ambiguous.class).isEmpty());
    }

    @Test public void objectFieldWithExactNameIsNotAcceptedAsPaint() {
        assertEquals("batteryPercentPaint",
                PaintFieldResolver.resolve(ObjectNamedPercent.class).orElseThrow().getName());
    }

    @Test public void staticPaintFieldIsRejected() {
        assertTrue(PaintFieldResolver.resolve(StaticPercent.class).isEmpty());
    }

    @Test public void exactNameInParentWinsOverChildFallbackAmbiguity() {
        assertEquals("percentInPaint", PaintFieldResolver
                .resolve(ChildWithAmbiguousFallback.class).orElseThrow().getName());
    }
}
