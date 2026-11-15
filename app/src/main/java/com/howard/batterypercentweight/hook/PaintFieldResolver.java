package com.howard.batterypercentweight.hook;

import android.graphics.Paint;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Locale;
import java.util.Optional;

public final class PaintFieldResolver {
    private PaintFieldResolver() {}

    public static Optional<Field> resolve(Class<?> targetClass) {
        if (targetClass == null) {
            return Optional.empty();
        }

        for (Class<?> current = targetClass; current != null; current = current.getSuperclass()) {
            for (Field field : current.getDeclaredFields()) {
                if (isInstancePaintField(field) && field.getName().equals("percentInPaint")) {
                    return accessible(field);
                }
            }
        }

        Field candidate = null;
        for (Class<?> current = targetClass; current != null; current = current.getSuperclass()) {
            for (Field field : current.getDeclaredFields()) {
                if (!isInstancePaintField(field)) {
                    continue;
                }
                if (field.getName().toLowerCase(Locale.ROOT).contains("percent")) {
                    if (candidate != null) {
                        return Optional.empty();
                    }
                    candidate = field;
                }
            }
        }
        return candidate == null ? Optional.empty() : accessible(candidate);
    }

    private static boolean isInstancePaintField(Field field) {
        return !Modifier.isStatic(field.getModifiers())
                && Paint.class.isAssignableFrom(field.getType());
    }

    private static Optional<Field> accessible(Field field) {
        try {
            field.setAccessible(true);
            return Optional.of(field);
        } catch (SecurityException ignored) {
            return Optional.empty();
        }
    }
}
