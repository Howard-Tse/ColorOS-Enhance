package dev.lsposed.fluidcloud;

import android.app.Notification;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.Icon;
import android.os.Bundle;
import android.os.UserHandle;
import android.service.notification.StatusBarNotification;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ImageSpan;
import android.util.TypedValue;
import java.lang.reflect.Method;
import java.util.LinkedHashMap;

/** Decorates the rendered card and capsule with the same profile badge. */
final class NotificationCloneBadge {
    private record BadgeKey(int serial, int density, int size) { }
    private record GlyphKey(BadgeKey badge, String pkg, int icon) { }
    static final String CAPSULE_ICON = "oplus_small_icon";
    private final Context context;
    private final Method badgeForDensity;
    private final LinkedHashMap<BadgeKey, Bitmap> badges = new LinkedHashMap<>();
    private final LinkedHashMap<GlyphKey, Icon> glyphs = new LinkedHashMap<>();

    NotificationCloneBadge(Context context) {
        this.context = context;
        Method method = null;
        try { method = Reflect.method(context.getPackageManager().getClass(),
                "getUserBadgeForDensity", Drawable.class, UserHandle.class, int.class); }
        catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) { /* Text fallback. */ }
        badgeForDensity = method;
    }

    void decorate(StatusBarNotification rendered, int serial) {
        var notification = rendered.getNotification();
        var extras = notification.extras;
        if (serial < 0 || extras == null) return;
        var title = extras.getCharSequence(Notification.EXTRA_TITLE);
        if (title == null || title.length() == 0) title = NotificationCollectionPage.appName(context, rendered.getPackageName());
        var metrics = context.getResources().getDisplayMetrics();
        int size = Math.max(1, Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 13, metrics)));
        Bitmap image = load(new BadgeKey(serial, metrics.densityDpi, size), rendered.getUser());
        var markedTitle = new SpannableStringBuilder(title).append('\u00a0');
        int start = markedTitle.length();
        markedTitle.append("分身");
        var badge = new BitmapDrawable(context.getResources(), image);
        badge.setBounds(0, 0, size, size);
        markedTitle.setSpan(new ImageSpan(badge, "分身", ImageSpan.ALIGN_CENTER), start, markedTitle.length(),
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        extras.putCharSequence(Notification.EXTRA_TITLE, markedTitle);
        addCapsuleBadge(notification.getSmallIcon(), extras, rendered.getUser(),
                new BadgeKey(serial, metrics.densityDpi, Math.max(1, Math.round(11 * metrics.density))));
    }

    private void addCapsuleBadge(Icon smallIcon, Bundle extras, UserHandle user, BadgeKey badge) {
        try {
            Icon custom = extras.getParcelable(CAPSULE_ICON, Icon.class);
            Icon source = custom == null ? smallIcon : custom;
            if (source == null) return;
            GlyphKey key = custom == null && source.getType() == Icon.TYPE_RESOURCE
                    ? new GlyphKey(badge, source.getResPackage(), source.getResId()) : null;
            Icon icon = key == null ? null : glyphs.get(key);
            if (icon == null) {
                Drawable base = source.loadDrawable(context);
                if (base == null) return;
                int side = Math.round(badge.size() * 2.55f);
                Bitmap bitmap = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888);
                Canvas canvas = new Canvas(bitmap);
                if (custom == null) base.mutate().setTint(Color.WHITE);
                int inset = Math.round(side * .07f);
                int edge = Math.round(side * .79f);
                base.setBounds(inset, inset, edge, edge);
                base.draw(canvas);
                float corner = Math.round(side * .48f);
                canvas.drawBitmap(load(badge, user), corner, corner, null);
                icon = Icon.createWithBitmap(bitmap);
                if (key != null) {
                    if (glyphs.size() >= 24) glyphs.remove(glyphs.keySet().iterator().next());
                    glyphs.put(key, icon);
                }
            }
            // ColorOS reads this extra for the left capsule icon. Leave the source icon alone.
            extras.putParcelable(CAPSULE_ICON, icon);
        } catch (RuntimeException | LinkageError unavailable) { /* Keep the original capsule icon. */ }
    }

    private Bitmap load(BadgeKey key, UserHandle user) {
        if (badges.containsKey(key)) return badges.get(key);
        Bitmap image = null;
        try {
            Drawable badge = badgeForDensity == null ? null
                    : (Drawable) Reflect.call(badgeForDensity, context.getPackageManager(), user, key.density());
            if (badge != null) {
                image = Bitmap.createBitmap(key.size(), key.size(), Bitmap.Config.ARGB_8888);
                badge.setBounds(0, 0, key.size(), key.size());
                badge.draw(new Canvas(image));
            }
        } catch (RuntimeException | LinkageError unavailable) {
            image = null;
        }
        if (image == null) image = fallbackBadge(key.size());
        if (badges.size() >= 24) badges.remove(badges.keySet().iterator().next());
        badges.put(key, image);
        return image;
    }

    private static Bitmap fallbackBadge(int size) {
        Bitmap image = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(image);
        canvas.scale(size / 16f, size / 16f);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setColor(0xff496281);
        canvas.drawRoundRect(0, 0, 16, 16, 4, 4, paint);
        paint.setColor(Color.WHITE);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(1.3f);
        canvas.drawRoundRect(3.5f, 3.5f, 10, 10, 1.2f, 1.2f, paint);
        canvas.drawRoundRect(6, 6, 12.5f, 12.5f, 1.2f, 1.2f, paint);
        return image;
    }
}
