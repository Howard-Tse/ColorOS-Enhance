package dev.lsposed.fluidcloud;

import java.util.Map;

public record CapsuleMarqueeOptions(boolean enabled, int speed, int pause, int repeats) {
    public static final String ENABLED = "capsule_marquee_enabled";
    public static final String SPEED = "capsule_marquee_speed";
    public static final String PAUSE = "capsule_marquee_pause";
    public static final String REPEATS = "capsule_marquee_repeats";
    public static final int MIN_SPEED = 10, MAX_SPEED = 100;
    public static final int MAX_PAUSE = 3000, MAX_REPEATS = 10;
    public static final CapsuleMarqueeOptions DEFAULTS = new CapsuleMarqueeOptions(false, 35, 800, 2);

    public CapsuleMarqueeOptions {
        speed = clamp(speed, MIN_SPEED, MAX_SPEED);
        pause = clamp(pause, 0, MAX_PAUSE);
        repeats = clamp(repeats, 0, MAX_REPEATS);
    }

    static CapsuleMarqueeOptions from(Map<String, ?> values) {
        return new CapsuleMarqueeOptions(Boolean.TRUE.equals(values.get(ENABLED)),
                number(values, SPEED, DEFAULTS.speed), number(values, PAUSE, DEFAULTS.pause),
                number(values, REPEATS, DEFAULTS.repeats));
    }

    private static int number(Map<String, ?> values, String key, int fallback) {
        return values.get(key) instanceof Integer value ? value : fallback;
    }

    private static int clamp(int value, int min, int max) {
        return Math.min(max, Math.max(min, value));
    }

    record Frame(float offset, long delay) { }

    Frame frame(float distance, float period, float density, long elapsed) {
        if (distance <= 0 || period <= distance || density <= 0) return new Frame(0, -1);
        double pixelsPerMs = speed * density / 1000.0;
        long travel = (long) Math.ceil(distance / pixelsPerMs);
        long wrap = (long) Math.ceil((period - distance) / pixelsPerMs);
        long cycle = travel + wrap + 2L * pause;
        if (repeats > 0 && elapsed >= repeats * cycle) return new Frame(0, -1);
        long phase = elapsed % cycle;
        if (phase < pause) return new Frame(0, pause - phase);
        phase -= pause;
        if (phase < travel) return new Frame(distance * phase / travel, Math.min(16, travel - phase));
        phase -= travel;
        if (phase < pause) return new Frame(distance, pause - phase);
        phase -= pause;
        return new Frame(distance + (period - distance) * phase / wrap, Math.min(16, wrap - phase));
    }
}
