package dev.lsposed.fluidcloud;

import android.graphics.Path;
import android.graphics.RectF;
import android.view.View.MeasureSpec;
import java.util.Map;

public record CloudCorners(Style capsule, Style card) {
    public static final int CARD_RADIUS_MAX_DP = 96;
    static final CloudCorners OEM = new CloudCorners(Style.OEM, Style.OEM);

    record Axes(float x, float y) {
        static Axes capsule(float radius, float width, float height) {
            return new Axes(Math.max(0f, Math.min(radius, width / 2f)),
                    Math.max(0f, Math.min(radius, height / 2f)));
        }

        Axes fromPill(float width, float height, float fraction) {
            float pill = Math.min(width, height) / 2f;
            return new Axes(pill + (x - pill) * fraction, pill + (y - pill) * fraction);
        }

        Axes inset(float inset) { return new Axes(Math.max(0, x - inset), Math.max(0, y - inset)); }
    }

    public record Style(boolean enabled, int dp, boolean continuous) {
        static final Style OEM = new Style(false, 0, false);

        float radius(float density, float width, float height) {
            return Math.min(requested(density), Math.min(width, height) / 2f);
        }

        float requested(float density) { return dp * density; }
    }

    static CloudCorners from(Map<String, ?> values) {
        return new CloudCorners(style(values, "capsule", 16, 24), style(values, "card", 28, CARD_RADIUS_MAX_DP));
    }

    private static Style style(Map<String, ?> values, String prefix, int fallback, int max) {
        if (!Boolean.TRUE.equals(values.get(prefix + "_corners_enabled"))) return Style.OEM;
        int dp = values.get(prefix + "_corner_dp") instanceof Integer number ? number : fallback;
        return new Style(true, Math.max(0, Math.min(dp, max)),
                Boolean.TRUE.equals(values.get(prefix + "_continuous_corners")));
    }

    static int compactCardHeight(int measured, int width, int heightSpec, float radius, int cameraInset) {
        int mode = MeasureSpec.getMode(heightSpec);
        if (mode == MeasureSpec.EXACTLY || width <= 0) return measured;
        // The parent adds its camera header separately. It already contributes to the outer corner height.
        int wanted = (int) Math.ceil(Math.min(radius * 2f, width)) - cameraInset;
        if (mode == MeasureSpec.AT_MOST) wanted = Math.min(wanted, MeasureSpec.getSize(heightSpec));
        return Math.max(measured, wanted);
    }

    public static void path(Path path, RectF bounds, float radius, boolean continuous) {
        float r = Math.max(0, Math.min(radius, Math.min(bounds.width(), bounds.height()) / 2f));
        path(path, bounds, new Axes(r, r), continuous);
    }

    static void path(Path path, RectF bounds, Axes axes, boolean continuous) {
        path.rewind();
        if (bounds.isEmpty()) return;
        Axes fitted = new Axes(Math.min(axes.x(), bounds.width() / 2f), Math.min(axes.y(), bounds.height() / 2f));
        float x = fitted.x(), y = fitted.y();
        if (!continuous || x == 0 || y == 0) {
            path.addRoundRect(bounds, x, y, Path.Direction.CW);
            return;
        }
        float l = bounds.left, t = bounds.top, w = bounds.right, b = bounds.bottom;
        // Two mirrored cubics per corner. The first two control points stay on
        // the straight edge, giving zero curvature at the edge, and share the
        // same tangent and curvature at the diagonal join.
        float px = .70f * x, qx = .38f * x, mx = .19f * x;
        float py = .70f * y, qy = .38f * y, my = .19f * y;
        path.moveTo(l + x, t);
        path.lineTo(w - x, t);
        path.cubicTo(w - px, t, w - qx, t, w - mx, t + my);
        path.cubicTo(w, t + qy, w, t + py, w, t + y);
        path.lineTo(w, b - y);
        path.cubicTo(w, b - py, w, b - qy, w - mx, b - my);
        path.cubicTo(w - qx, b, w - px, b, w - x, b);
        path.lineTo(l + x, b);
        path.cubicTo(l + px, b, l + qx, b, l + mx, b - my);
        path.cubicTo(l, b - qy, l, b - py, l, b - y);
        path.lineTo(l, t + y);
        path.cubicTo(l, t + py, l, t + qy, l + mx, t + my);
        path.cubicTo(l + qx, t, l + px, t, l + x, t);
        path.close();
    }
}
