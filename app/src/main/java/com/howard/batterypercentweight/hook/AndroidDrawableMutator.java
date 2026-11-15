package com.howard.batterypercentweight.hook;

import android.content.Context;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;

import com.howard.batterypercentweight.TextAdjustmentConfig;
import com.howard.batterypercentweight.WeightConfig;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

@FunctionalInterface
interface ErrorLogger {
    void log(String message, Throwable error);
}

public final class AndroidDrawableMutator implements DrawableMutator {
    private final ErrorLogger errorLogger;

    public AndroidDrawableMutator(ErrorLogger errorLogger) {
        this.errorLogger = errorLogger;
    }

    @Override
    public InitialBaseline captureInitialBaseline(Object drawable, Field paintField) {
        try {
            Paint paint = readPaint(drawable, paintField);
            return new InitialBaseline(
                    new FontBaseline(paint.getTypeface(), null), paint.getTextSize());
        } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
            log("Unable to capture initial percentage Paint baseline", error);
            return null;
        }
    }

    @Override
    public Object captureFontBaseline(
            Object drawable, Field paintField, String systemVariation) {
        try {
            Paint paint = readPaint(drawable, paintField);
            return new FontBaseline(paint.getTypeface(), systemVariation);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
            log("Unable to capture percentage Paint font baseline", error);
            return null;
        }
    }

    @Override
    public float resolveDensity(Object drawable, Method contextGetter) {
        try {
            Object value = contextGetter.invoke(drawable);
            if (!(value instanceof Context context)) {
                throw new IllegalStateException("Resolved drawable Context is unavailable");
            }
            return context.getResources().getDisplayMetrics().density;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
            log("Unable to resolve battery drawable density", error);
            return Float.NaN;
        }
    }

    @Override
    public boolean applyWeight(Object drawable, Field paintField, int weight) {
        try {
            Paint paint = readPaint(drawable, paintField);
            int normalizedWeight = WeightConfig.normalize(weight);
            if (paint.setFontVariationSettings(
                    WeightConfig.variationString(weight))) {
                return true;
            }

            Typeface weightedTypeface =
                    Typeface.create(paint.getTypeface(), normalizedWeight, false);
            if (weightedTypeface == null) {
                return false;
            }
            paint.setTypeface(weightedTypeface);
            return true;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
            log("Unable to apply percentage Paint weight", error);
            return false;
        }
    }

    @Override
    public boolean restoreWeight(
            Object drawable, Field paintField, Object fontBaseline) {
        try {
            if (!(fontBaseline instanceof FontBaseline captured)) {
                return false;
            }
            Paint paint = readPaint(drawable, paintField);
            paint.setTypeface(captured.typeface());
            String systemVariation = captured.systemVariation();
            if (systemVariation != null && !systemVariation.trim().isEmpty()) {
                paint.setFontVariationSettings(systemVariation);
            }
            return true;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
            log("Unable to restore percentage Paint baseline", error);
            return false;
        }
    }

    @Override
    public boolean applyTextSize(
            Object drawable, Field paintField, int tenthsDp, float density) {
        try {
            Paint paint = readPaint(drawable, paintField);
            float requestedPx = TextAdjustmentConfig.toPx(tenthsDp, density);
            if (Float.compare(paint.getTextSize(), requestedPx) == 0) {
                return false;
            }
            paint.setTextSize(requestedPx);
            return true;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
            log("Unable to apply percentage Paint text size", error);
            return false;
        }
    }

    @Override
    public boolean restoreTextSize(
            Object drawable, Field paintField, float textSizePx) {
        try {
            Paint paint = readPaint(drawable, paintField);
            if (Float.compare(paint.getTextSize(), textSizePx) == 0) {
                return false;
            }
            paint.setTextSize(textSizePx);
            return true;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
            log("Unable to restore percentage Paint text size", error);
            return false;
        }
    }

    @Override
    public boolean recomputeTextBounds(Object drawable, Method boundsUpdater) {
        try {
            boundsUpdater.invoke(drawable);
            return true;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
            log("Unable to recompute percentage text bounds", error);
            return false;
        }
    }

    @Override
    public void invalidate(Object drawable) {
        try {
            if (drawable instanceof Drawable androidDrawable) {
                androidDrawable.invalidateSelf();
            }
        } catch (RuntimeException | LinkageError error) {
            log("Unable to invalidate battery drawable", error);
        }
    }

    private static Paint readPaint(Object drawable, Field field)
            throws ReflectiveOperationException {
        Object value = field.get(drawable);
        if (value instanceof Paint paint) {
            return paint;
        }
        throw new IllegalStateException("Resolved percentage Paint is unavailable");
    }

    private void log(String message, Throwable error) {
        try {
            errorLogger.log(message, error);
        } catch (RuntimeException | LinkageError ignored) {
            // Diagnostics must never destabilize SystemUI.
        }
    }

    public record FontBaseline(Typeface typeface, String systemVariation) {
    }
}
