package dev.lsposed.fluidcloud;

import android.content.Context;
import android.graphics.Canvas;
import android.view.View;
import java.util.List;

/** Full-card decoration plane outside the OEM body's blur/alpha layer. No SDK view is moved. */
@android.annotation.SuppressLint("ViewConstructor")
final class CameraMaterialSurface extends View {
    record Paint(CardHeaderDrawable drawable, float alpha) { }
    private List<Paint> paints = List.of();
    private final Runnable changed = this::invalidate;
    private Object outlineFrame;
    CameraMaterialSurface(Context context, View owner) {
        super(context);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        setFocusable(false); setClickable(false);
        var reference = new java.lang.ref.WeakReference<>(owner);
        setOutlineProvider(new android.view.ViewOutlineProvider() {
            @Override public void getOutline(View view, android.graphics.Outline outline) {
                View card = reference.get();
                var provider = card == null ? null : card.getOutlineProvider();
                if (provider != null) provider.getOutline(card, outline);
                else outline.setRect(0, 0, view.getWidth(), view.getHeight());
            }
        });
        setClipToOutline(true);
    }
    void update(List<Paint> next) {
        for (Paint old : paints) if (next.stream().noneMatch(p -> p.drawable() == old.drawable()))
            old.drawable().materialOwner(null);
        for (Paint paint : next) paint.drawable().materialOwner(changed);
        if (!paints.equals(next)) { paints = List.copyOf(next); invalidate(); }
    }
    void release() { update(List.of()); }
    void updateOutline(Object frame) {
        if (!java.util.Objects.equals(outlineFrame, frame)) { outlineFrame = frame; invalidateOutline(); }
    }
    @Override protected void onDraw(Canvas canvas) {
        for (Paint paint : paints) {
            if (paint.alpha() <= 0) continue;
            int save = paint.alpha() < 1 ? canvas.saveLayerAlpha(0, 0, getWidth(), getHeight(),
                    Math.round(paint.alpha() * 255)) : canvas.save();
            try { paint.drawable().drawMaterial(canvas, 0, 0, getWidth(), getHeight()); }
            finally { canvas.restoreToCount(save); }
        }
    }
}
