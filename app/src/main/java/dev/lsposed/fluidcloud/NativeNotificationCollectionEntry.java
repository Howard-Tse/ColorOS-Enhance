package dev.lsposed.fluidcloud;

import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;

/** A native ColorOS custom row at the end of the shade's render tree. */
final class NativeNotificationCollectionEntry {
    static final int MAX_ICONS = 4;
    private final Constructor<?> node, controller, entry;
    private final Field container;
    private final Class<?> rootType;
    private final Method parent, inflate, rowView, content;
    private final NotificationCloudFeature feature;
    private ViewGroup stack;
    private View entryRow;
    private Object owner;
    private LinearLayout icons;
    private List<String> displayed = List.of();
    private Field emptyRow, emptyVisible;
    private Method setEmptyVisible;
    private View maskedEmpty;
    private boolean emptyRequested, applyingEmpty;
    private final Material material;

    NativeNotificationCollectionEntry(NotificationCloudFeature feature, ClassLoader loader,
            Class<?> spec, Class<?> nodeController) throws ReflectiveOperationException {
        this.feature = feature;
        String render = "com.android.systemui.statusbar.notification.collection.render.";
        rootType = Reflect.type(loader, render + "RootNodeController");
        Class<?> list = Reflect.type(loader, "com.android.systemui.statusbar.notification.stack.NotificationListContainer");
        container = Reflect.field(rootType, "listContainer", list);
        parent = Reflect.method(list, "getViewParentForNotification", ViewGroup.class);
        node = Reflect.type(loader, render + "NodeSpecImpl").getConstructor(spec, nodeController);
        Class<?> type = Reflect.type(loader, "com.oplus.systemui.statusbar.notification.customcard.OplusCustomRowController");
        Class<?> row = Reflect.type(loader, "com.oplus.systemui.statusbar.notification.customcard.OplusCustomRow");
        Class<?> data = Reflect.type(loader, "com.android.systemui.plugins.custom.CustomRowEntry");
        controller = type.getConstructor(Context.class);
        entry = data.getConstructor(String.class, View.class, List.class, int.class, String.class, String.class,
                boolean.class, boolean.class, String.class, boolean.class);
        inflate = Reflect.method(type, "reinflateView", void.class, ViewGroup.class, data);
        rowView = Reflect.method(type, "getView", row);
        content = Reflect.method(row, "setNewSeedlingView", void.class, View.class);
        Material nativeMaterial;
        try { nativeMaterial = new Material(loader, row); }
        catch (ReflectiveOperationException | RuntimeException | LinkageError unavailable) {
            feature.failure("Standard notification material unavailable for collection entry", unavailable);
            nativeMaterial = null;
        }
        material = nativeMaterial;
        try { alignSwipeHighlight(loader); }
        catch (ReflectiveOperationException | RuntimeException | LinkageError unavailable) {
            feature.failure("Collection row uses stock highlight bounds", unavailable);
        }
        try { installEmptyShade(loader); }
        catch (ReflectiveOperationException | RuntimeException | LinkageError unavailable) {
            feature.failure("Notification collection empty-state adapter unavailable", unavailable);
        }
    }

    /** SystemUI includes child swipe offset in its clip. A whole-row swipe already moved the canvas. */
    private void alignSwipeHighlight(ClassLoader loader) throws ReflectiveOperationException {
        Class<?> outline = Reflect.type(loader, "com.android.systemui.statusbar.notification.row.ExpandableOutlineView");
        Class<?> ext = Reflect.type(loader, "com.oplus.systemui.statusbar.notification.row.OplusExpandableOutlineViewExImpl");
        Class<?> clip = Reflect.type(loader, "com.oplus.systemui.notification.material.NotificationSpotLightDelegate$ClipSpec");
        Method bounds = Reflect.method(ext, "getSpotLightClipSpec", clip, outline);
        Field wholeRow = Reflect.field(outline, "mDismissUsingRowTranslationX", boolean.class);
        SwipeClip nativeClip = new SwipeClip(clip);
        feature.module.hook(bounds).setExceptionMode(io.github.libxposed.api.XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept(chain -> {
                    Object result = chain.proceed();
                    if (entryRow == null || chain.getArg(0) != entryRow || result == null
                            || !wholeRow.getBoolean(entryRow)) return result;
                    return nativeClip.local(result, (int) entryRow.getTranslationX());
                });
    }

    static final class SwipeClip {
        private final Method copy;
        private final Field left, right, top, bottom, topRadius, bottomRadius, smooth;

        SwipeClip(Class<?> clip) throws ReflectiveOperationException {
            copy = Reflect.method(clip, "copy", clip, int.class, int.class, int.class, int.class,
                    float.class, float.class, boolean.class);
            left = Reflect.field(clip, "left", int.class);
            right = Reflect.field(clip, "right", int.class);
            top = Reflect.field(clip, "top", int.class);
            bottom = Reflect.field(clip, "bottom", int.class);
            topRadius = Reflect.field(clip, "topCornerRadius", float.class);
            bottomRadius = Reflect.field(clip, "bottomCornerRadius", float.class);
            smooth = Reflect.field(clip, "useSmoothRadius", boolean.class);
        }

        Object local(Object clip, int offset) throws IllegalAccessException {
            if (offset == 0) return clip;
            return Reflect.call(copy, clip, left.getInt(clip) - Math.max(0, offset), top.getInt(clip),
                    right.getInt(clip) - Math.min(0, offset), bottom.getInt(clip),
                    topRadius.getFloat(clip), bottomRadius.getFloat(clip), smooth.getBoolean(clip));
        }
    }

    ViewGroup listFor(Object root) {
        return rootType.isInstance(root) ? (ViewGroup) Reflect.call(parent, Reflect.get(container, root)) : null;
    }

    @SuppressWarnings("unchecked")
    boolean append(Object root, Object spec, List<?> children, List<String> packages) throws ReflectiveOperationException {
        if (packages.isEmpty()) return false;
        ViewGroup list = listFor(root);
        if (list == null) return false;
        if (owner == null || stack != list) create(list);
        if (!displayed.equals(packages)) {
            updateIcons(packages);
            displayed = List.copyOf(packages);
        }
        ((List<Object>) children).add(node.newInstance(spec, owner));
        hideEmptyShade();
        return true;
    }

    private void installEmptyShade(ClassLoader loader) throws ReflectiveOperationException {
        Class<?> decor = Reflect.type(loader, "com.android.systemui.statusbar.notification.row.StackScrollerDecorView");
        Class<?> list = Reflect.type(loader, "com.android.systemui.statusbar.notification.stack.NotificationStackScrollLayout");
        emptyRow = Reflect.field(list, "mEmptyShadeView", decor);
        emptyVisible = Reflect.field(decor, "mIsVisible", boolean.class);
        setEmptyVisible = Reflect.uniqueMethod(decor, method -> {
            Class<?>[] args = method.getParameterTypes();
            return method.getName().equals("setVisible") && method.getReturnType() == void.class
                    && args.length == 3 && args[0] == boolean.class && args[1] == boolean.class;
        });
        feature.module.hook(setEmptyVisible)
                .setExceptionMode(io.github.libxposed.api.XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
                    if (applyingEmpty || chain.getThisObject() != maskedEmpty) return chain.proceed();
                    emptyRequested = (boolean) chain.getArg(0);
                    return chain.proceed(new Object[]{false, false, chain.getArg(2)});
                });
    }

    private void hideEmptyShade() {
        if (setEmptyVisible == null || !emptyRow.getDeclaringClass().isInstance(stack)) return;
        View empty = (View) Reflect.get(emptyRow, stack);
        if (empty == maskedEmpty) return;
        restoreEmptyShade();
        if (empty != null) {
            maskedEmpty = empty;
            emptyRequested = Boolean.TRUE.equals(Reflect.get(emptyVisible, empty));
            applyingEmpty = true;
            try { Reflect.call(setEmptyVisible, empty, false, false, null); }
            finally { applyingEmpty = false; }
        }
    }

    void restoreEmptyShade() {
        if (maskedEmpty == null) return;
        View previous = maskedEmpty;
        maskedEmpty = null;
        Reflect.call(setEmptyVisible, previous, emptyRequested, false, null);
    }

    private void create(ViewGroup list) throws ReflectiveOperationException {
        Context context = list.getContext();
        LinearLayout body = new LinearLayout(context);
        body.setLayoutParams(new android.widget.FrameLayout.LayoutParams(-1, -2));
        body.setGravity(Gravity.CENTER_VERTICAL);
        body.setMinimumHeight(dp(context, 60));
        body.setPaddingRelative(dp(context, 18), dp(context, 8), dp(context, 14), dp(context, 8));
        body.setFocusable(true);
        body.setOnClickListener(view -> feature.openCollection(view, list));

        int tint = primaryText(context);
        TextView title = notificationTitle(context, body);
        title.setText("已转为流体云卡片");
        title.setTextColor(tint);
        title.setMaxLines(2);
        title.setEllipsize(TextUtils.TruncateAt.END);
        body.addView(title, new LinearLayout.LayoutParams(0, -2, 1f));
        LinearLayout apps = new LinearLayout(context);
        apps.setGravity(Gravity.CENTER_VERTICAL);
        apps.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        body.addView(apps, new LinearLayout.LayoutParams(-2, -2));
        ImageView arrow = new ImageView(context);
        arrow.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        arrow.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        arrow.setImageTintList(android.content.res.ColorStateList.valueOf(tint));
        arrow(context, arrow);
        LinearLayout.LayoutParams arrowSpace = new LinearLayout.LayoutParams(dp(context, 22), dp(context, 22));
        arrowSpace.setMarginStart(dp(context, 6));
        body.addView(arrow, arrowSpace);

        String key = "coloros_enhance_collection_entry";
        Object data = entry.newInstance(key, body, List.of(), 0,
                context.getPackageName(), key, false, false, "", false);
        Object nativeController = controller.newInstance(context);
        Reflect.call(inflate, nativeController, list, data);
        View row = (View) Reflect.call(rowView, nativeController);
        entryRow = row;
        Reflect.call(content, row, body);
        if (material != null) row.addOnAttachStateChangeListener(material);
        stack = list;
        owner = nativeController;
        icons = apps;
        displayed = List.of();
    }

    private void updateIcons(List<String> packages) {
        icons.removeAllViews();
        Context context = icons.getContext();
        PackageManager manager = context.getPackageManager();
        for (String pkg : packages) {
            ImageView icon = new ImageView(context);
            try { icon.setImageDrawable(manager.getApplicationIcon(pkg)); }
            catch (PackageManager.NameNotFoundException unavailable) { continue; }
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(context, 22), dp(context, 22));
            params.setMarginStart(dp(context, 4));
            icons.addView(icon, params);
            if (icons.getChildCount() == MAX_ICONS) break;
        }
    }

    @android.annotation.SuppressLint("DiscouragedApi")
    private static TextView notificationTitle(Context context, ViewGroup parent) {
        var resources = context.getResources();
        int layout = resources.getIdentifier("notification_2025_title", "layout", "android");
        if (layout != 0) try {
            if (LayoutInflater.from(context).inflate(layout, parent, false) instanceof TextView title) {
                title.setId(View.NO_ID);
                return title;
            }
        } catch (android.view.InflateException unavailable) {
            // Older firmware may provide only the notification title text appearance.
        }
        TextView title = new TextView(context);
        title.setTextAppearance(android.R.style.TextAppearance_Material_Notification_Title);
        title.setTypeface(Typeface.create("sans-serif-semibold", Typeface.NORMAL));
        title.setIncludeFontPadding(false);
        return title;
    }

    @android.annotation.SuppressLint("DiscouragedApi")
    private static int primaryText(Context context) {
        int id = context.getResources().getIdentifier("notification_material_blur_text_color_common", "color", "com.android.systemui");
        return context.getColor(id != 0 ? id : android.R.color.white);
    }

    private static final class Material implements View.OnAttachStateChangeListener {
        private final Method background, require, refresh, release, update;
        private final Field manager;
        private final Object notification;

        Material(ClassLoader loader, Class<?> row) throws ReflectiveOperationException {
            Class<?> view = Reflect.type(loader, "com.android.systemui.statusbar.notification.row.NotificationBackgroundView");
            Class<?> blur = Reflect.type(loader, "com.oplus.systemui.notification.blur.ViewBlurManager");
            Class<?> type = Reflect.type(loader, blur.getName() + "$CardType");
            Class<?> proxy = Reflect.type(loader, "com.oplusos.systemui.common.blurability.ViewBlurProxy");
            background = Reflect.method(row, "getMBackgroundNormal", view);
            update = Reflect.method(row, "updateResource", void.class, boolean.class);
            manager = Reflect.field(row, "viewBlurManager", blur);
            require = Reflect.method(blur, "requireBlurProxyForView", proxy, View.class, type);
            refresh = Reflect.method(blur, "refreshBlurType", void.class, View.class);
            release = Reflect.method(blur, "releaseBlurProxyForView", void.class, View.class, boolean.class, String.class);
            notification = Reflect.get(Reflect.field(type, "NOTIFICATION", type), null);
        }

        @Override public void onViewAttachedToWindow(View row) {
            try {
                Object blur = Reflect.get(manager, row);
                View bg = (View) Reflect.call(background, row);
                if (blur == null || bg == null) return;
                Reflect.call(require, blur, bg, notification);
                Reflect.call(update, row, true);
                Reflect.call(refresh, blur, bg);
            } catch (RuntimeException | LinkageError unavailable) { /* Keep the native custom-row background. */ }
        }

        @Override public void onViewDetachedFromWindow(View row) {
            try {
                Object blur = Reflect.get(manager, row);
                View bg = (View) Reflect.call(background, row);
                if (blur != null && bg != null) Reflect.call(release, blur, bg, true, "Notification collection row detached");
            } catch (RuntimeException | LinkageError unavailable) { /* Stock row detach still runs. */ }
        }
    }

    @android.annotation.SuppressLint("DiscouragedApi")
    private static void arrow(Context context, ImageView arrow) {
        var resources = context.getResources();
        int id = resources.getIdentifier("ic_chevron_right", "drawable", "com.android.systemui");
        if (id == 0) {
            id = resources.getIdentifier("ic_chevron_left", "drawable", "com.android.systemui");
            arrow.setScaleX(-1f);
        }
        if (id == 0) {
            id = resources.getIdentifier("coui_back_arrow", "drawable", "com.android.systemui");
            arrow.setScaleX(-1f);
        }
        if (id != 0) arrow.setImageResource(id);
        else {
            arrow.setScaleX(1f);
            arrow.setImageDrawable(context.getDrawable(android.R.drawable.ic_media_next));
        }
    }

    private static int dp(Context context, int value) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, context.getResources().getDisplayMetrics()));
    }
}
