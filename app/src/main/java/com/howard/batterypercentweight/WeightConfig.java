package com.howard.batterypercentweight;

public final class WeightConfig {
    public static final int MIN = 100;
    public static final int MAX = 1000;
    public static final int STEP = 10;
    public static final int DEFAULT = 570;

    private WeightConfig() {
    }

    public static int normalize(int raw) {
        int bounded = Math.max(MIN, Math.min(MAX, raw));
        return MIN + ((bounded - MIN + STEP / 2) / STEP) * STEP;
    }

    public static int toProgress(int weight) {
        return (normalize(weight) - MIN) / STEP;
    }

    public static int fromProgress(int progress) {
        int maxProgress = (MAX - MIN) / STEP;
        return MIN + Math.max(0, Math.min(maxProgress, progress)) * STEP;
    }

    public static String variationString(int weight) {
        return "'wght' " + normalize(weight);
    }
}
