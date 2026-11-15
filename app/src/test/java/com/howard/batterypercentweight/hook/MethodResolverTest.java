package com.howard.batterypercentweight.hook;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.RectF;
import android.graphics.Typeface;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public final class MethodResolverTest {
    // Signatures taken from the supplied ColorOS 17 HorizontalBatteryContentDrawable.
    private static final class ColorOs17Horizontal {
        public float layoutTextX;
        public float layoutBaselineY;
        public void drawContent(Canvas canvas, RectF bounds) {}
        public void setFontTypeface(Typeface typeface, int weight) {}
        public void updatePercentInTextBounds() {}
        public void updateBatteryLevel() {}
    }

    @Test public void modernPercentDrawingRequiresDedicatedLayoutContract() {
        assertTrue(MethodResolver.findPercentContentDrawer(ColorOs17Horizontal.class).isPresent());
        assertTrue(MethodResolver.findPercentContentDrawer(ExactTypographyMethods.class).isEmpty());
        assertTrue(MethodResolver.findPercentContentDrawer(ChargingContent.class).isEmpty());
        assertTrue(MethodResolver.findPercentContentDrawer(InheritedChargingContent.class).isEmpty());
    }

    private static class ChargingContent {
        public void drawContent(Canvas canvas, RectF bounds) {}
    }

    private static final class InheritedChargingContent extends ChargingContent {
        public float layoutTextX;
        public float layoutBaselineY;
        public void updatePercentInTextBounds() {}
    }

    @Test public void colorOs17TypefaceCallbackIsResolved() {
        assertTrue("ColorOS 17 font callback must be hooked",
                MethodResolver.findTypefaceSetter(ColorOs17Horizontal.class).isPresent());
        assertEquals(2, MethodResolver.findTypefaceSetter(ColorOs17Horizontal.class)
                .orElseThrow().getParameterCount());
    }

    @Test public void colorOs17RecomputesInkBoundsWithoutChangingBatteryLevel() {
        assertEquals("updatePercentInTextBounds",
                MethodResolver.findBoundsUpdater(ColorOs17Horizontal.class)
                        .orElseThrow().getName());
    }

    private static final class Exact {
        private void setFontVar(String variation) {}
        private void setFontVar(CharSequence variation) {}
    }

    private static final class WrongOverload {
        private static void setFontVar(String variation) {}
        private void setFontVar(CharSequence variation) {}
    }

    private static class ParentFallback {
        private void setFontVar(String variation) {}
    }

    private static final class ChildFallback extends ParentFallback {}

    private static class ParentMatch {
        private void setFontVar(String variation) {}
    }

    private static final class ChildMatch extends ParentMatch {
        private void setFontVar(String variation) {}
    }

    private static final class ExactTypographyMethods {
        private void setFontTypeface(Typeface typeface) {}
        private static void setFontTypeface(CharSequence typeface) {}

        private float getOffsetX() { return 0.5f; }
        private static float getOffsetX(int ignored) { return 0.0f; }
        private int getOffsetX(String ignored) { return 0; }

        private float getTextMarginTop() { return 0.27f; }
        private static float getTextMarginTop(int ignored) { return 0.0f; }
        private int getTextMarginTop(String ignored) { return 0; }

        public void updateBatteryLevel() {}
        private static void updateBatteryLevel(String ignored) {}
        private int updateBatteryLevel(int ignored) { return 0; }

        public Context getContext() { return null; }
        private static Context getContext(String ignored) { return null; }
        private Object getContext(int ignored) { return null; }
    }

    private static final class MissingOffsetX {
        private float getTextMarginTop() { return 0.27f; }
    }

    private static final class StaticTypographyMethods {
        private static void setFontTypeface(Typeface typeface) {}
        private static float getOffsetX() { return 0.5f; }
        private static float getTextMarginTop() { return 0.27f; }
        private static void updateBatteryLevel() {}
        private static Context getContext() { return null; }
    }

    private static final class WrongSignatureTypographyMethods {
        private void setFontTypeface(String typeface) {}
        private int getOffsetX() { return 0; }
        private float getTextMarginTop(int ignored) { return 0.0f; }
        private int updateBatteryLevel() { return 0; }
        private Object getContext() { return null; }
    }

    private static class ParentDrawableMethods {
        public void updateBatteryLevel() {}
        public Context getContext() { return null; }
    }

    private static final class VerifiedDrawableMethods extends ParentDrawableMethods {
        @Override public void updateBatteryLevel() {}

        @Override public Context getContext() { return null; }
    }

    @Test public void exactNonStaticVoidStringMethodIsAccepted() {
        assertEquals("setFontVar",
                MethodResolver.findFontVariationSetter(Exact.class).orElseThrow().getName());
    }

    @Test public void wrongOverloadsAreRejected() {
        assertTrue(MethodResolver.findFontVariationSetter(WrongOverload.class).isEmpty());
    }

    @Test public void inheritedExactMethodIsAccepted() {
        assertEquals(ParentFallback.class,
                MethodResolver.findFontVariationSetter(ChildFallback.class)
                        .orElseThrow().getDeclaringClass());
    }

    @Test public void multipleHierarchyMatchesAreRejected() {
        assertTrue(MethodResolver.findFontVariationSetter(ChildMatch.class).isEmpty());
    }

    @Test public void resolvesExactTypographyMethodsIndependently() {
        assertTrue(MethodResolver.findTypefaceSetter(ExactTypographyMethods.class).isPresent());
        assertTrue(MethodResolver.findOffsetXAccessor(ExactTypographyMethods.class).isPresent());
        assertTrue(MethodResolver.findTextMarginTopAccessor(ExactTypographyMethods.class).isPresent());
        assertTrue(MethodResolver.findBoundsUpdater(ExactTypographyMethods.class).isPresent());
        assertTrue(MethodResolver.findContextGetter(ExactTypographyMethods.class).isPresent());
    }

    @Test public void missingOffsetXDoesNotPreventTextMarginTopResolution() {
        assertTrue(MethodResolver.findOffsetXAccessor(MissingOffsetX.class).isEmpty());
        assertEquals("getTextMarginTop",
                MethodResolver.findTextMarginTopAccessor(MissingOffsetX.class)
                        .orElseThrow()
                        .getName());
    }

    @Test public void staticTypographyMethodsAreRejected() {
        assertTrue(MethodResolver.findTypefaceSetter(StaticTypographyMethods.class).isEmpty());
        assertTrue(MethodResolver.findOffsetXAccessor(StaticTypographyMethods.class).isEmpty());
        assertTrue(MethodResolver.findTextMarginTopAccessor(StaticTypographyMethods.class).isEmpty());
        assertTrue(MethodResolver.findBoundsUpdater(StaticTypographyMethods.class).isEmpty());
        assertTrue(MethodResolver.findContextGetter(StaticTypographyMethods.class).isEmpty());
    }

    @Test public void wrongTypographySignaturesAreRejected() {
        assertTrue(MethodResolver.findTypefaceSetter(WrongSignatureTypographyMethods.class).isEmpty());
        assertTrue(MethodResolver.findOffsetXAccessor(WrongSignatureTypographyMethods.class).isEmpty());
        assertTrue(MethodResolver.findTextMarginTopAccessor(WrongSignatureTypographyMethods.class).isEmpty());
        assertTrue(MethodResolver.findBoundsUpdater(WrongSignatureTypographyMethods.class).isEmpty());
        assertTrue(MethodResolver.findContextGetter(WrongSignatureTypographyMethods.class).isEmpty());
    }

    @Test public void closestDrawableOverridesAreSelectedForUpdateAndContext() {
        assertEquals(VerifiedDrawableMethods.class,
                MethodResolver.findBoundsUpdater(VerifiedDrawableMethods.class)
                        .orElseThrow()
                        .getDeclaringClass());
        assertEquals(VerifiedDrawableMethods.class,
                MethodResolver.findContextGetter(VerifiedDrawableMethods.class)
                        .orElseThrow()
                        .getDeclaringClass());
    }

    @Test public void nullTypesFailClosed() {
        assertTrue(MethodResolver.findTypefaceSetter(null).isEmpty());
        assertTrue(MethodResolver.findOffsetXAccessor(null).isEmpty());
        assertTrue(MethodResolver.findTextMarginTopAccessor(null).isEmpty());
        assertTrue(MethodResolver.findBoundsUpdater(null).isEmpty());
        assertTrue(MethodResolver.findContextGetter(null).isEmpty());
    }
}
