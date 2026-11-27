package dev.lsposed.fluidcloud;

import android.graphics.Canvas;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.os.Handler;
import android.os.SystemClock;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextDirectionHeuristics;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.Display;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import io.github.libxposed.api.XposedInterface;
import java.lang.ref.WeakReference;
import java.util.WeakHashMap;

/** Scrolls stock single-line labels inside their current native layout. */
final class NativeCapsuleMarquee {
    private static final String CAPSULE = NativeCapsuleAppearance.VIEWS + "CapsuleView";
    private static final int MAX_TEXT_LENGTH = 5120;
    private static final ThreadLocal<View> presentation = new ThreadLocal<>();

    // A provider that draws glyphs itself may supply a different string or paint to
    // Canvas. Only replace the standard TextView draw of plugin text components.
    private static final ClassValue<Boolean> standardDraw = new ClassValue<>() {
        @Override protected Boolean computeValue(Class<?> type) {
            if (!type.getName().startsWith("com.oplus.systemui.plugins.shared.")) return false;
            for (Class<?> current = type; current != TextView.class; current = current.getSuperclass()) {
                try {
                    current.getDeclaredMethod("onDraw", Canvas.class);
                    return false;
                } catch (NoSuchMethodException ignored) { }
            }
            return true;
        }
    };

    private final HookEntry module;
    private final Handler handler;
    private final WeakHashMap<TextView, Line> lines = new WeakHashMap<>();
    private CapsuleMarqueeOptions options = CapsuleMarqueeOptions.DEFAULTS;
    private XposedInterface.HookHandle hook;

    NativeCapsuleMarquee(HookEntry module, Handler handler) {
        this.module = module;
        this.handler = handler;
    }

    boolean configure(CapsuleMarqueeOptions next) {
        if (options.equals(next)) return false;
        options = next;
        if (!next.enabled()) {
            if (hook != null) { hook.unhook(); hook = null; }
            clear();
            return true;
        }
        if (hook == null) {
            try {
                var draw = Reflect.method(TextView.class, "onDraw", void.class, Canvas.class);
                hook = module.hook(draw).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                        .intercept(chain -> draw((TextView) chain.getThisObject(), (Canvas) chain.getArg(0))
                                ? null : chain.proceed());
                module.info("Capsule text marquee installed");
            } catch (ReflectiveOperationException | RuntimeException | LinkageError unavailable) {
                module.error("Capsule text marquee unavailable", unavailable);
            }
        }
        clear();
        return true;
    }

    void refresh(View root) {
        if (root instanceof TextView text && standardDraw.get(text.getClass())) text.invalidate();
        if (root instanceof ViewGroup group) for (int index = 0; index < group.getChildCount(); index++) {
            refresh(group.getChildAt(index));
        }
    }

    static View presenting(View target) {
        View previous = presentation.get();
        if (target == null) presentation.remove();
        else presentation.set(target);
        return previous;
    }

    private boolean draw(TextView view, Canvas canvas) {
        if (!options.enabled() || !standardDraw.get(view.getClass())) return false;
        View output = presentation.get();
        View capsule = capsuleOf(view);
        Layout nativeLayout = view.getLayout();
        CharSequence text = view.getText();
        int left = view.getCompoundPaddingLeft(), right = view.getWidth() - view.getCompoundPaddingRight();
        if (capsule == null || capsule.getVisibility() != View.VISIBLE || capsule.getAlpha() <= 0
                || !visible(view, output) || right <= left || nativeLayout == null || nativeLayout.getLineCount() != 1
                || TextUtils.isEmpty(text) || text.length() > MAX_TEXT_LENGTH) {
            forget(view);
            return false;
        }

        boolean rtl = nativeLayout.getParagraphDirection(0) < 0;
        Line line = lines.computeIfAbsent(view, key -> new Line(key));
        line.update(text, view, right - left, rtl);
        if (line.layout == null) return false;
        long now = SystemClock.uptimeMillis();
        if (line.started < 0) line.started = now;
        // Pull the final glyphs clear of the stock trailing fade during the last pause.
        int trailing = !rtl && view.getForeground() instanceof LayerDrawable layers && layers.getNumberOfLayers() == 1
                && layers.getDrawable(0) instanceof GradientDrawable fade
                ? Math.max(0, Math.min((right - left) / 2, fade.getIntrinsicWidth())) : 0;
        float density = view.getResources().getDisplayMetrics().density;
        float gap = Math.min(16 * density, (right - left - trailing) / 2f);
        float period = line.layout.getWidth() + trailing + gap;
        var frame = options.frame(line.distance + trailing, period, density, now - line.started);
        int save = canvas.save();
        try {
            int x = view.getScrollX(), y = view.getScrollY();
            canvas.clipRect(x + left, y, x + right, y + view.getHeight());
            float start = rtl ? right - line.layout.getWidth() + frame.offset() : left - frame.offset();
            canvas.translate(x + start, y + view.getBaseline() - line.layout.getLineBaseline(0));
            line.paint.setColor(view.getCurrentTextColor());
            line.paint.drawableState = view.getDrawableState();
            line.layout.draw(canvas);
            // At the end of the period the entering copy occupies the original
            // text position, so resetting the offset does not move the pixels.
            if (frame.offset() + right - left > period) {
                canvas.translate(rtl ? -period : period, 0f);
                line.layout.draw(canvas);
            }
        } finally { canvas.restoreToCount(save); }
        line.schedule(output, now, frame.delay());
        return true;
    }

    private static View capsuleOf(View view) {
        for (View current = view; current != null; current = current.getParent() instanceof View parent ? parent : null) {
            if (CAPSULE.equals(current.getClass().getName())) return current;
        }
        return null;
    }

    private static boolean visible(View view, View output) {
        if (!view.isAttachedToWindow()) return false;
        if (output == null) {
            if (view.getWindowVisibility() != View.VISIBLE || !view.isShown()) return false;
        } else if (!output.isShown()) return false;
        Display display = view.getDisplay();
        return display == null || display.getState() == Display.STATE_ON;
    }

    void hidden(View capsule) {
        lines.entrySet().removeIf(entry -> {
            if (capsuleOf(entry.getKey()) != capsule) return false;
            entry.getValue().stop();
            entry.getKey().invalidate();
            return true;
        });
    }

    private void forget(TextView view) {
        Line line = lines.remove(view);
        if (line != null) line.stop();
    }

    private void clear() {
        lines.forEach((view, line) -> {
            View output = line.output.get();
            line.stop();
            view.invalidate();
            if (output != null) output.invalidate();
        });
        lines.clear();
    }

    private final class Line {
        final WeakReference<TextView> view;
        final TextPaint paint = new TextPaint();
        WeakReference<View> output = new WeakReference<>(null);
        CharSequence label;
        StaticLayout layout;
        int viewport;
        boolean rtl, includePad;
        float distance;
        long started = -1, scheduled = -1;
        final Runnable redraw = this::redraw;

        Line(TextView view) { this.view = new WeakReference<>(view); }

        void update(CharSequence text, TextView textView, int width, boolean rtlText) {
            if (TextUtils.equals(label, text) && viewport == width && rtl == rtlText
                    && includePad == textView.getIncludeFontPadding() && paint.equalsForTextMeasurement(textView.getPaint())) return;
            stop();
            label = TextUtils.stringOrSpannedString(text);
            viewport = width;
            rtl = rtlText;
            includePad = textView.getIncludeFontPadding();
            paint.set(textView.getPaint());
            layout = null;
            float naturalWidth = Layout.getDesiredWidth(label, paint);
            if (!(naturalWidth > viewport + 1) || naturalWidth > 65_536) return;
            layout = StaticLayout.Builder.obtain(label, 0, label.length(), paint, (int) Math.ceil(naturalWidth) + 1)
                    .setTextDirection(rtl ? TextDirectionHeuristics.RTL : TextDirectionHeuristics.LTR)
                    .setIncludePad(includePad).setMaxLines(1).build();
            distance = layout.getLineWidth(0) - viewport;
            if (distance <= 1) layout = null;
        }

        void schedule(View target, long now, long delay) {
            if (output.get() != target) output = new WeakReference<>(target);
            if (delay < 0) return;
            long when = now + delay;
            if (scheduled >= 0 && scheduled <= when) return;
            if (scheduled >= 0) handler.removeCallbacks(redraw);
            scheduled = when;
            handler.postAtTime(redraw, when);
        }

        void redraw() {
            scheduled = -1;
            TextView labelView = view.get();
            View target = output.get();
            if (labelView == null || !visible(labelView, target)) {
                started = -1;
                return;
            }
            labelView.invalidate();
            if (target != null) target.invalidate();
        }

        void stop() {
            if (scheduled >= 0) handler.removeCallbacks(redraw);
            scheduled = started = -1;
        }
    }
}
