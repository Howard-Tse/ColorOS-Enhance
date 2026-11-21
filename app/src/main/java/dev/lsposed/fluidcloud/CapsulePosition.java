package dev.lsposed.fluidcloud;

import java.util.Map;

/** Screen-relative offsets from the factory placement, in dp. */
public record CapsulePosition(int horizontalDp, int verticalDp) {
    public static final int HORIZONTAL_LIMIT_DP = 120;
    public static final int VERTICAL_LIMIT_DP = 32;
    static final CapsulePosition OEM = new CapsulePosition(0, 0);

    public CapsulePosition {
        horizontalDp = Math.max(-HORIZONTAL_LIMIT_DP, Math.min(HORIZONTAL_LIMIT_DP, horizontalDp));
        verticalDp = Math.max(-VERTICAL_LIMIT_DP, Math.min(VERTICAL_LIMIT_DP, verticalDp));
    }

    static CapsulePosition from(Map<String, ?> values) {
        return new CapsulePosition(values.get("capsule_offset_x_dp") instanceof Integer x ? x : 0,
                values.get("capsule_offset_y_dp") instanceof Integer y ? y : 0);
    }

    static int fit(int offset, int start, int end, int available) {
        if (offset < 0) return Math.max(offset, Math.min(0, -start));
        return Math.min(offset, Math.max(0, available - end));
    }
}
