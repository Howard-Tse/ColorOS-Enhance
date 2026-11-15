package com.howard.batterypercentweight.hook;

import android.graphics.Canvas;

final class PercentContentDrawing {
    private PercentContentDrawing() {}

    @FunctionalInterface
    interface DrawCall { Object draw() throws Throwable; }

    static Object draw(Canvas canvas, float dx, float dy, DrawCall original) throws Throwable {
        if (dx == 0.0f && dy == 0.0f) return original.draw();
        int saveCount = canvas.save();
        try {
            canvas.translate(dx, dy);
            return original.draw();
        } finally {
            canvas.restoreToCount(saveCount);
        }
    }
}
