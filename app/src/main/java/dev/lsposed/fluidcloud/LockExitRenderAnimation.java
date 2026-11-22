package dev.lsposed.fluidcloud;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RecordingCanvas;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;

/** Optional HWUI canvas-property animation, using the same model as the UI fallback. */
final class LockExitRenderAnimation {
    private record Contract(Method createFloat, Method createPaint, Method roundRect, Method target,
                            Constructor<?> floatAnimator, Constructor<?> paintAnimator, int paintAlpha) { }

    private static final Contract HWUI = load();

    private final Object[] properties;
    private final ArrayList<Animator> animators = new ArrayList<>(7);
    private boolean started;

    private static Contract load() {
        try {
            ClassLoader loader = Canvas.class.getClassLoader();
            Class<?> property = Reflect.type(loader, "android.graphics.CanvasProperty");
            Class<?> animator = Reflect.type(loader, "android.graphics.animation.RenderNodeAnimator");
            return new Contract(Reflect.method(property, "createFloat", property, float.class),
                    Reflect.method(property, "createPaint", property, Paint.class),
                    Reflect.method(RecordingCanvas.class, "drawRoundRect", void.class,
                            property, property, property, property, property, property, property),
                    Reflect.method(animator, "setTarget", void.class, RecordingCanvas.class),
                    animator.getConstructor(property, float.class),
                    animator.getConstructor(property, int.class, float.class),
                    Reflect.field(animator, "PAINT_ALPHA", int.class).getInt(null));
        } catch (ReflectiveOperationException | RuntimeException | LinkageError unavailable) {
            return null;
        }
    }

    static boolean available() { return HWUI != null; }

    LockExitRenderAnimation(LockExitMotion motion, Paint paint, long duration, Runnable finished)
            throws ReflectiveOperationException {
        float[] start = motion.start().values(), dot = motion.dot().values(), end = motion.end().values();
        properties = new Object[start.length];
        int alpha = properties.length - 1;
        for (int i = 0; i < alpha; i++) {
            properties[i] = Reflect.call(HWUI.createFloat, null, start[i]);
            if (start[i] == dot[i] && start[i] == end[i]) continue;
            // When the intermediate dot differs but the endpoints coincide,
            // use a nonzero native range and yield the right endpoint via the LUT.
            float target = end[i] == start[i] ? start[i] + 1f : end[i];
            Animator animator = (Animator) HWUI.floatAnimator.newInstance(properties[i], target);
            configure(animator, start[i], dot[i], end[i], target, duration);
            animators.add(animator);
        }
        properties[alpha] = Reflect.call(HWUI.createPaint, null, paint);
        Animator fade = (Animator) HWUI.paintAnimator.newInstance(properties[alpha], HWUI.paintAlpha, end[alpha]);
        fade.setDuration(duration);
        fade.setInterpolator(progress -> 1f - LockExitMotion.opacity(progress));
        fade.addListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(Animator animation) { finished.run(); }
        });
        animators.add(fade);
    }

    private static void configure(Animator animator, float start, float dot, float end, float target, long duration) {
        animator.setDuration(duration);
        animator.setInterpolator(progress -> (LockExitMotion.value(start, dot, end, progress) - start) / (target - start));
    }

    boolean draw(Canvas canvas) {
        if (!(canvas instanceof RecordingCanvas)) return false;
        if (!started) {
            for (Animator animator : animators) {
                Reflect.call(HWUI.target, animator, canvas);
                animator.start();
            }
            started = true;
        }
        Reflect.call(HWUI.roundRect, canvas, properties);
        return true;
    }

    void cancel() {
        for (Animator animator : animators) if (animator.isRunning()) animator.cancel();
    }
}
