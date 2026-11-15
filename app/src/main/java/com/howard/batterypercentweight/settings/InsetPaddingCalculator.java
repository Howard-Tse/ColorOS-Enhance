package com.howard.batterypercentweight.settings;

import java.util.Objects;

public final class InsetPaddingCalculator {
    private final Edges base;

    public InsetPaddingCalculator(Edges base) {
        this.base = Objects.requireNonNull(base);
    }

    public Edges calculate(Edges systemBars, Edges displayCutout) {
        Objects.requireNonNull(systemBars);
        Objects.requireNonNull(displayCutout);
        return new Edges(
                base.left() + Math.max(systemBars.left(), displayCutout.left()),
                base.top() + Math.max(systemBars.top(), displayCutout.top()),
                base.right() + Math.max(systemBars.right(), displayCutout.right()),
                base.bottom() + Math.max(systemBars.bottom(), displayCutout.bottom()));
    }

    public record Edges(int left, int top, int right, int bottom) {
    }
}
