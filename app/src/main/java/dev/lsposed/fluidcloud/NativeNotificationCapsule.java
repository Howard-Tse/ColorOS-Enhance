package dev.lsposed.fluidcloud;

import android.animation.Animator;
import android.app.Notification;
import android.content.Context;
import android.content.res.Configuration;
import android.os.Build;
import android.os.Bundle;
import android.service.notification.StatusBarNotification;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.View;
import dev.howard.colorosenhance.notifications.NotificationCloudRules;
import io.github.libxposed.api.XposedInterface;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONException;

/** Switches the right-hand caption in the OEM capsule parser without altering card content. */
final class NativeNotificationCapsule {
    static final String SHORT_CRITICAL_TEXT = "android.shortCriticalText";
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");
    private record Render(Notification notification, String caption) { }
    private final ThreadLocal<Render> parsing = new ThreadLocal<>();
    private final ArrayList<XposedInterface.HookHandle> hooks = new ArrayList<>();
    private final Context plugin;
    private final NotificationCloudFeature feature;
    private final Method viewItem;
    private final Field key;
    private final TextPaint captionPaint = new TextPaint();
    private Configuration captionConfiguration;
    private float captionDensity, captionChrome;

    NativeNotificationCapsule(NotificationCloudFeature feature, ClassLoader loader, Context plugin)
            throws ReflectiveOperationException {
        this.plugin = plugin;
        this.feature = feature;
        Class<?> parser = Reflect.type(loader, "com.oplus.systemui.plugins.shared.data.parse.s");
        Class<?> label = Reflect.type(loader, "com.oplus.systemui.plugins.shared.template.component.model.r");
        Method caption = Reflect.method(parser, "i", label, Notification.class);
        Class<?> capsule = Reflect.type(loader, "com.oplus.systemui.plugins.seedling.capsule.ui.view.CapsuleView");
        Class<?> item = Reflect.type(loader, "com.oplus.systemui.plugins.seedling.capsule.ui.model.g");
        viewItem = Reflect.method(capsule, "getViewItem", item);
        key = Reflect.field(item, "a", String.class);
        var measure = Reflect.method(capsule, "onMeasure", void.class, int.class, int.class);
        try {
            hooks.add(feature.module.hook(caption).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
                Render render = parsing.get();
                if (render == null || render.notification() != chain.getArg(0) || Build.VERSION.SDK_INT < 36) return chain.proceed();
                return readWithCaption(plugin, render.notification(), render.caption(), input ->
                        input == render.notification() ? chain.proceed() : chain.proceed(new Object[]{input}));
            }));
            hooks.add(feature.module.hook(measure).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
                int limit = widthLimit((View) chain.getThisObject());
                if (limit == Integer.MAX_VALUE) return chain.proceed();
                int requested = (int) chain.getArg(0);
                if (View.MeasureSpec.getMode(requested) != View.MeasureSpec.UNSPECIFIED)
                    limit = Math.min(limit, View.MeasureSpec.getSize(requested));
                return chain.proceed(new Object[]{View.MeasureSpec.makeMeasureSpec(limit, View.MeasureSpec.AT_MOST), chain.getArg(1)});
            }));
        } catch (RuntimeException | LinkageError unavailable) { close(); throw unavailable; }
        try {
            var drawableWidth = Reflect.method(capsule, "setCapsuleDrawableWidth", void.class, int.class);
            hooks.add(feature.module.hook(drawableWidth).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
                int requested = (int) chain.getArg(0), bounded = Math.min(requested, widthLimit((View) chain.getThisObject()));
                return requested == bounded ? chain.proceed() : chain.proceed(new Object[]{bounded});
            }));
            installWidthAnimation(loader, capsule);
        }
        catch (ReflectiveOperationException | RuntimeException | LinkageError unavailable) {
            feature.failure("Notification capsule width-animation adapter unavailable", unavailable);
        }
    }

    interface CaptionReader<T> { T read(Notification input) throws Throwable; }

    static <T> T readWithCaption(Context context, Notification render, String label, CaptionReader<T> reader) throws Throwable {
        // The render copy is private to this parse. The native caption helper reads
        // this extra synchronously. Restore it before any other template is parsed.
        // Let Android sanitize unusually long strings on the established path.
        if (label.length() <= 512) {
            Bundle original = render.extras;
            Bundle captionExtras = original == null ? new Bundle() : new Bundle(original);
            captionExtras.putString(SHORT_CRITICAL_TEXT, label);
            render.extras = captionExtras;
            try {
                if (label.equals(render.getShortCriticalText())) return reader.read(render);
            } finally { render.extras = original; }
        }
        Notification input = Notification.Builder.recoverBuilder(context, render.clone()).setShortCriticalText(label).build();
        return reader.read(input);
    }

    private void installWidthAnimation(ClassLoader loader, Class<?> capsule) throws ReflectiveOperationException {
        String base = "com.oplus.systemui.plugins.seedling.capsule.";
        Class<?> callback = Reflect.type(loader, base + "data.repo.d");
        var owner = Reflect.field(callback, "c", Reflect.type(loader, base + "data.model.e"));
        Field templateView = templateViewField(callback);
        var start = Reflect.method(callback, "x", void.class, Bundle.class);
        hooks.add(feature.module.hook(start).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
            Object bound = Reflect.get(owner, chain.getThisObject());
            if (!capsule.isInstance(bound)) return chain.proceed();
            int limit = widthLimit((View) bound);
            if (limit == Integer.MAX_VALUE) return chain.proceed();
            Bundle params = (Bundle) chain.getArg(0);
            View template = templateView == null ? null : (View) Reflect.get(templateView, chain.getThisObject());
            Bundle capped = constrainWidthAnimation(params, limit, template);
            if (capped == null) return null;
            return capped == params ? chain.proceed() : chain.proceed(new Object[]{capped});
        }));
    }

    private static Field templateViewField(Class<?> callback) {
        try { return Reflect.field(callback, "b", View.class); }
        catch (NoSuchFieldException missing) { return null; }
    }

    static Bundle constrainWidthAnimation(Bundle params, int limit, View template) {
        String steps = params.getString("animValue");
        if (steps == null || params.getInt("type", -1) != 0) return params;
        WidthAnimation result;
        try { result = boundedAnimation(steps, limit); }
        catch (JSONException invalid) { return params; }
        if (result == null) return params;
        if (result.stationary() && applyStationaryTemplateUpdate(params, template)) return null;
        Bundle capped = new Bundle(params);
        capped.putString("animValue", result.segments());
        return capped;
    }

    private static boolean applyStationaryTemplateUpdate(Bundle params, View template) {
        int tag = params.getInt("animObjTag", 0);
        if (template == null || tag == 0 || !(template.getTag(tag) instanceof Animator animator)) return false;
        // The native width event also starts the template animator that commits the new text.
        // Use ColorOS's zero-duration start for this case without animating the status bar.
        if (!animator.isStarted()) {
            animator.setDuration(0L);
            animator.start();
        }
        return true;
    }

    record WidthAnimation(String segments, boolean stationary) { }

    static WidthAnimation boundedAnimation(String steps, int limit) throws JSONException {
        JSONArray segments = new JSONArray(steps);
        boolean changed = false, stationary = true;
        Integer position = null;
        for (int i = 0; i < segments.length(); i++) {
            var segment = segments.getJSONObject(i);
            int from = segment.getInt("startValue"), to = segment.getInt("endValue");
            int cappedStart = Math.min(limit, from), cappedEnd = Math.min(limit, to);
            if (from != cappedStart || to != cappedEnd) {
                segment.put("startValue", cappedStart);
                segment.put("endValue", cappedEnd);
                changed = true;
            }
            if (position == null) position = cappedStart;
            stationary &= cappedStart == position && cappedEnd == position;
        }
        return changed ? new WidthAnimation(segments.toString(), stationary) : null;
    }

    int widthLimit(View view) {
        int max = feature.capsuleMaxDp();
        if (max == 0 || !feature.enabled() || !viewItem.getDeclaringClass().isInstance(view)) return Integer.MAX_VALUE;
        Object shown = Reflect.call(viewItem, view);
        if (shown == null || feature.store.get((String) Reflect.get(key, shown)) == null) return Integer.MAX_VALUE;
        return Math.round(Math.max(48, max) * view.getResources().getDisplayMetrics().density);
    }

    String caption(StatusBarNotification sbn, Context host, NotificationCloudRules rules, int maxDp) {
        String text = captionText(sbn, host, rules);
        return maxDp > 0 ? limit(text, maxDp) : text;
    }

    static String captionText(StatusBarNotification sbn, Context host, NotificationCloudRules rules) {
        Notification n = sbn.getNotification();
        CharSequence label = null;
        if (rules.capsuleText() == NotificationCloudRules.CapsuleText.CONTENT && n.extras != null) {
            label = n.extras.getCharSequence(Notification.EXTRA_TEXT);
            if (TextUtils.isEmpty(label)) label = n.extras.getCharSequence(Notification.EXTRA_TITLE);
        }
        if (TextUtils.isEmpty(label)) label = NotificationCollectionPage.appName(host, sbn.getPackageName());
        return WHITESPACE.matcher(label).replaceAll(" ").trim();
    }

    Object parse(Method method, Object parser, StatusBarNotification sbn, String label) {
        if (TextUtils.isEmpty(label)) return Reflect.call(method, parser, sbn);
        parsing.set(new Render(sbn.getNotification(), label));
        try { return Reflect.call(method, parser, sbn); }
        finally { parsing.remove(); }
    }

    private String limit(String text, int maxDp) {
        var resources = plugin.getResources();
        var config = resources.getConfiguration();
        float density = resources.getDisplayMetrics().density;
        if (captionConfiguration == null || density != captionDensity || !config.equals(captionConfiguration)) {
            captionConfiguration = new Configuration(config);
            captionDensity = density;
            captionPaint.setTextSize(dimension("capsule_text_size", 13));
            captionChrome = 2 * dimension("capsule_padding_start_end", 10)
                    + dimension("capsule_icon_size", 20) + dimension("capsule_sym_text_margin", 6);
        }
        return TextUtils.ellipsize(text, captionPaint, Math.max(0, Math.max(48, maxDp) * density - captionChrome),
                TextUtils.TruncateAt.END).toString();
    }

    @android.annotation.SuppressLint("DiscouragedApi")
    private float dimension(String name, int fallback) {
        var resources = plugin.getResources();
        int id = resources.getIdentifier(name, "dimen", plugin.getPackageName());
        return id == 0 ? fallback * resources.getDisplayMetrics().density : resources.getDimensionPixelSize(id);
    }
    void close() { hooks.forEach(XposedInterface.HookHandle::unhook); hooks.clear(); }
}
