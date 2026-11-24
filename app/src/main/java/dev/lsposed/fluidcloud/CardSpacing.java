package dev.lsposed.fluidcloud;

import java.util.Map;

/** All four values are signed dp adjustments to the template's own dimensions. */
public record CardSpacing(int side, int gap, int horizontal, int vertical) {
    public static final String SIDE = "card_side_margin_dp";
    public static final String GAP = "card_gap_dp";
    public static final String HORIZONTAL = "card_content_horizontal_dp";
    public static final String VERTICAL = "card_content_vertical_dp";
    public static final int MIN = -32, MAX = 48;
    static final CardSpacing OEM = new CardSpacing(0, 0, 0, 0);

    static CardSpacing from(Map<String, ?> values) {
        return new CardSpacing(read(values, SIDE), read(values, GAP), read(values, HORIZONTAL), read(values, VERTICAL));
    }

    private static int read(Map<String, ?> values, String key) {
        return values.get(key) instanceof Integer value ? Math.clamp(value, MIN, MAX) : 0;
    }

    boolean hasPadding() { return horizontal != 0 || vertical != 0; }

    static int width(int stock, int available, int sidePx, int minimum) {
        if (stock <= 0 || available <= 0 || sidePx == 0) return stock;
        return Math.clamp(stock - 2 * sidePx, Math.min(stock, Math.min(minimum, available)), available);
    }
}
