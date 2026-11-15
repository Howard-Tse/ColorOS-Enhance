package com.howard.batterypercentweight.hook;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.RectF;
import android.graphics.Typeface;

import java.lang.reflect.Method;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.Optional;

public final class MethodResolver {
    private MethodResolver() {}

    public static Optional<Method> findFontVariationSetter(Class<?> targetClass) {
        if (targetClass == null) {
            return Optional.empty();
        }

        Method candidate = null;
        for (Class<?> current = targetClass; current != null; current = current.getSuperclass()) {
            for (Method method : current.getDeclaredMethods()) {
                if (!isFontVariationSetter(method)) {
                    continue;
                }
                if (candidate != null) {
                    return Optional.empty();
                }
                candidate = method;
            }
        }
        return candidate == null ? Optional.empty() : accessible(candidate);
    }

    public static Optional<Method> findTypefaceSetter(Class<?> type) {
        Optional<Method> modern = findNearestExact(
                type, "setFontTypeface", Void.TYPE, Typeface.class, Integer.TYPE);
        return modern.isPresent() ? modern
                : findNearestExact(type, "setFontTypeface", Void.TYPE, Typeface.class);
    }

    public static Optional<Method> findOffsetXAccessor(Class<?> type) {
        return findNearestExact(type, "getOffsetX", Float.TYPE);
    }

    public static Optional<Method> findTextMarginTopAccessor(Class<?> type) {
        return findNearestExact(type, "getTextMarginTop", Float.TYPE);
    }

    public static Optional<Method> findBoundsUpdater(Class<?> type) {
        Optional<Method> modern = findNearestExact(type, "updatePercentInTextBounds", Void.TYPE);
        return modern.isPresent() ? modern
                : findNearestExact(type, "updateBatteryLevel", Void.TYPE);
    }

    public static Optional<Method> findPercentContentDrawer(Class<?> type) {
        if (type == null) return Optional.empty();
        Optional<Method> measure = findNearestExact(type, "updatePercentInTextBounds", Void.TYPE);
        Optional<Method> draw = findNearestExact(
                type, "drawContent", Void.TYPE, Canvas.class, RectF.class);
        // Never hook the inherited base drawContent: that method draws charging icons.
        if (measure.isEmpty() || measure.get().getDeclaringClass() != type
                || draw.isEmpty() || draw.get().getDeclaringClass() != type) {
            return Optional.empty();
        }
        try {
            for (String name : new String[]{"layoutTextX", "layoutBaselineY"}) {
                Field field = type.getDeclaredField(name);
                if (field.getType() != Float.TYPE || Modifier.isStatic(field.getModifiers())) {
                    return Optional.empty();
                }
            }
            return draw;
        } catch (NoSuchFieldException | SecurityException ignored) {
            return Optional.empty();
        }
    }

    public static Optional<Method> findContextGetter(Class<?> type) {
        return findNearestExact(type, "getContext", Context.class);
    }

    private static boolean isFontVariationSetter(Method method) {
        return method.getName().equals("setFontVar")
                && method.getParameterCount() == 1
                && method.getParameterTypes()[0] == String.class
                && method.getReturnType() == Void.TYPE
                && !Modifier.isStatic(method.getModifiers());
    }

    private static Optional<Method> findNearestExact(
            Class<?> type, String name, Class<?> returnType, Class<?>... parameterTypes) {
        if (type == null) {
            return Optional.empty();
        }

        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            Method candidate = null;
            for (Method method : current.getDeclaredMethods()) {
                if (!isExactInstanceMethod(method, name, returnType, parameterTypes)) {
                    continue;
                }
                if (candidate != null) {
                    return Optional.empty();
                }
                candidate = method;
            }
            if (candidate != null) {
                return accessible(candidate);
            }
        }
        return Optional.empty();
    }

    private static boolean isExactInstanceMethod(
            Method method, String name, Class<?> returnType, Class<?>... parameterTypes) {
        return !method.isSynthetic()
                && !method.isBridge()
                && method.getName().equals(name)
                && method.getReturnType() == returnType
                && Arrays.equals(method.getParameterTypes(), parameterTypes)
                && !Modifier.isStatic(method.getModifiers());
    }

    private static Optional<Method> accessible(Method method) {
        try {
            method.setAccessible(true);
            return Optional.of(method);
        } catch (RuntimeException ignored) {
            return Optional.empty();
        }
    }
}
