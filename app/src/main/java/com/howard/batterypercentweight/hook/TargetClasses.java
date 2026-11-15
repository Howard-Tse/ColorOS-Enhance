package com.howard.batterypercentweight.hook;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

public final class TargetClasses {
    private static final String PACKAGE_PREFIX =
            "com.oplus.systemui.statusbar.pipeline.battery.ui.drawable.";

    public static final List<String> CANDIDATES = List.of(
            PACKAGE_PREFIX + "HorizontalBatteryContentDrawable",
            PACKAGE_PREFIX + "VerticalBatteryContentDrawable",
            PACKAGE_PREFIX + "CircleBatteryContentDrawable");

    private TargetClasses() {
    }

    public static List<ResolvedTarget> resolve(ClassLookup lookup) {
        List<ResolvedTarget> targets = new ArrayList<>();
        for (String candidate : CANDIDATES) {
            try {
                Class<?> type = lookup.load(candidate);
                PaintFieldResolver.resolve(type)
                        .ifPresent(field -> targets.add(new ResolvedTarget(type, field)));
            } catch (ClassNotFoundException | LinkageError | RuntimeException ignored) {
                // Unsupported firmware candidate.
            }
        }
        return List.copyOf(targets);
    }

    @FunctionalInterface
    public interface ClassLookup {
        Class<?> load(String name) throws ClassNotFoundException;
    }

    public record ResolvedTarget(Class<?> type, Field paintField) {
    }
}
