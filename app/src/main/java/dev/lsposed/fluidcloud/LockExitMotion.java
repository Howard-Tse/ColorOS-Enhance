package dev.lsposed.fluidcloud;

import android.graphics.RectF;

/** Shrink to the camera dot first, then shrink and fade the dot. */
record LockExitMotion(Frame start, Frame dot, Frame end) {
    static final float DOT_START = .67f;

    record Frame(float left, float top, float right, float bottom,
                 float radiusX, float radiusY, float alpha) {
        float[] values() {
            return new float[]{left, top, right, bottom, radiusX, radiusY, alpha};
        }
    }

    static LockExitMotion from(RectF bounds, float cameraX, float radius) {
        float y = bounds.centerY();
        float dotRadius = Math.min(bounds.width(), bounds.height()) / 2f;
        return new LockExitMotion(
                new Frame(bounds.left, bounds.top, bounds.right, bounds.bottom,
                        Math.min(radius, bounds.width() / 2f), Math.min(radius, bounds.height() / 2f), 255f),
                new Frame(cameraX - dotRadius, y - dotRadius, cameraX + dotRadius, y + dotRadius,
                        dotRadius, dotRadius, 255f),
                new Frame(cameraX, y, cameraX, y, 0f, 0f, 0f));
    }

    Frame frame(float progress) {
        return new Frame(value(start.left, dot.left, end.left, progress),
                value(start.top, dot.top, end.top, progress),
                value(start.right, dot.right, end.right, progress),
                value(start.bottom, dot.bottom, end.bottom, progress),
                value(start.radiusX, dot.radiusX, end.radiusX, progress),
                value(start.radiusY, dot.radiusY, end.radiusY, progress),
                255f * opacity(progress));
    }

    static float opacity(float progress) {
        return CapsuleMotion.dotOpacity(value(1f, 1f, 0f, progress));
    }

    static float value(float start, float dot, float end, float progress) {
        if (progress < DOT_START) return start + (dot - start) * (progress / DOT_START);
        return dot + (end - dot) * ((progress - DOT_START) / (1f - DOT_START));
    }
}
