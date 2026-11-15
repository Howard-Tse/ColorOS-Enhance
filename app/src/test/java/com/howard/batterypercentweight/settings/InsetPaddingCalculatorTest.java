package com.howard.batterypercentweight.settings;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public final class InsetPaddingCalculatorTest {
    @Test public void addsBasePaddingToLargestInsetOnEveryEdge() {
        InsetPaddingCalculator calculator = new InsetPaddingCalculator(
                new InsetPaddingCalculator.Edges(10, 20, 30, 40));

        InsetPaddingCalculator.Edges result = calculator.calculate(
                new InsetPaddingCalculator.Edges(1, 9, 20, 3),
                new InsetPaddingCalculator.Edges(8, 4, 2, 12));

        assertEquals(new InsetPaddingCalculator.Edges(18, 29, 50, 52), result);
    }

    @Test public void repeatedCalculationUsesFixedBaseWithoutAccumulatingInsets() {
        InsetPaddingCalculator calculator = new InsetPaddingCalculator(
                new InsetPaddingCalculator.Edges(24, 24, 24, 24));
        InsetPaddingCalculator.Edges systemBars =
                new InsetPaddingCalculator.Edges(0, 80, 0, 40);
        InsetPaddingCalculator.Edges displayCutout =
                new InsetPaddingCalculator.Edges(12, 100, 8, 0);

        InsetPaddingCalculator.Edges first =
                calculator.calculate(systemBars, displayCutout);
        InsetPaddingCalculator.Edges second =
                calculator.calculate(systemBars, displayCutout);

        assertEquals(new InsetPaddingCalculator.Edges(36, 124, 32, 64), first);
        assertEquals(first, second);
    }
}
