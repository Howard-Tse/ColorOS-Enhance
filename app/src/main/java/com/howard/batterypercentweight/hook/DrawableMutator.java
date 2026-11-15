package com.howard.batterypercentweight.hook;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

interface DrawableMutator {
    record InitialBaseline(Object fontState, float textSizePx) {
    }

    InitialBaseline captureInitialBaseline(Object drawable, Field paintField);

    Object captureFontBaseline(
            Object drawable, Field paintField, String systemVariation);

    float resolveDensity(Object drawable, Method contextGetter);

    boolean applyWeight(Object drawable, Field paintField, int weight);

    boolean restoreWeight(Object drawable, Field paintField, Object fontBaseline);

    boolean applyTextSize(
            Object drawable, Field paintField, int tenthsDp, float density);

    boolean restoreTextSize(Object drawable, Field paintField, float textSizePx);

    boolean recomputeTextBounds(Object drawable, Method boundsUpdater);

    void invalidate(Object drawable);
}
