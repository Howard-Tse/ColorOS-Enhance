package dev.lsposed.fluidcloud;

import android.app.AlertDialog;
import android.app.Notification;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.Uri;
import android.os.Bundle;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.Icon;
import android.service.notification.StatusBarNotification;
import android.widget.Toast;
import dev.howard.colorosenhance.notifications.NotificationCloudRules;
import dev.howard.colorosenhance.notifications.NotificationCloudStore;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** PendingIntents retain their creator and destination; reply/authentication stays with the native row. */
final class NotificationCloudActions {
    private record FloatingButton(PendingIntent target, PendingIntent button) { }

    private final NotificationCloudFeature feature;
    private final Context context;
    private final NativeNotificationFloating floating;
    private Bitmap iconPreview, comparisonPreview;
    private final Map<String, FloatingButton> floatingButtons = new HashMap<>();
    private final java.util.Map<String, Runnable> menuCommands = new java.util.LinkedHashMap<>();
    private final String action = "com.android.systemui.COLOROS_ENHANCE_NOTIFICATION_" + java.util.UUID.randomUUID();
    NotificationCloudActions(NotificationCloudFeature feature, Context context) {
        this.feature = feature; this.context = context;
        NativeNotificationFloating zoom = null;
        try { zoom = new NativeNotificationFloating(context); }
        catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
            feature.failure("ColorOS notification floating window unavailable", failure);
        }
        floating = zoom;
        context.registerReceiver(new BroadcastReceiver() {
            @Override public void onReceive(Context ignored, Intent intent) {
                String floatKey = intent.getStringExtra("floatKey");
                if (floatKey != null) {
                    var entry = feature.store.get(floatKey);
                    PendingIntent original = intent.getParcelableExtra("target", PendingIntent.class);
                    if (original != null && entry != null && original.equals(entry.value().getNotification().contentIntent)
                            && feature.rules().floating(entry.app())) openFloating(floatKey);
                    return;
                }
                Runnable command = menuCommands.remove(intent.getStringExtra("menu"));
                if (command != null && feature.enabled() && feature.unlocked()) command.run();
            }
        }, menuFilter(action), Context.RECEIVER_NOT_EXPORTED);
    }
    record MenuAction(String label, Runnable run) { }
    PendingIntent menuIntent(String key, MenuAction item) {
        var original = feature.store.get(key);
        String token = java.util.UUID.randomUUID().toString();
        menuCommands.put(token, () -> {
            var current = feature.store.get(key);
            if (original != null && current != null && sameActions(
                    original.value().getNotification(), current.value().getNotification())) item.run().run();
        });
        feature.main.postDelayed(() -> menuCommands.remove(token), 120_000);
        while (menuCommands.size() > 128) menuCommands.remove(menuCommands.keySet().iterator().next());
        Intent intent = new Intent(action).setPackage(context.getPackageName())
                .setData(Uri.parse("coloros-enhance://menu/" + token)).putExtra("menu", token);
        return PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_IMMUTABLE);
    }
    static IntentFilter menuFilter(String action) {
        IntentFilter filter = new IntentFilter(action);
        filter.addDataScheme("coloros-enhance");
        return filter;
    }
    private static boolean sameActions(Notification before, Notification current) {
        if (before == current) return true;
        if (!java.util.Objects.equals(before.contentIntent, current.contentIntent)) return false;
        if (before.actions == current.actions) return true;
        if (before.actions == null || current.actions == null || before.actions.length != current.actions.length) return false;
        for (int i = 0; i < before.actions.length; i++) {
            var a = before.actions[i];
            var b = current.actions[i];
            if (a == b) continue;
            if (a == null || b == null || !java.util.Objects.equals(a.actionIntent, b.actionIntent)
                    || !android.text.TextUtils.equals(a.title, b.title)
                    || a.isAuthenticationRequired() != b.isAuthenticationRequired()
                    || (a.getRemoteInputs() == null) != (b.getRemoteInputs() == null)) return false;
        }
        return true;
    }
    private PendingIntent floatingButton(String key, long revision, PendingIntent target) {
        FloatingButton cached = floatingButtons.get(key);
        if (cached != null && cached.target().equals(target)) return cached.button();
        var intent = new Intent(action).setPackage(context.getPackageName())
                .setData(Uri.parse("coloros-enhance://floating/" + revision))
                .putExtra("floatKey", key).putExtra("target", target);
        PendingIntent button = PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_IMMUTABLE);
        floatingButtons.put(key, new FloatingButton(target, button));
        return button;
    }
    void retainFloatingButtons(Set<String> liveKeys) { floatingButtons.keySet().retainAll(liveKeys); }
    @SuppressWarnings("deprecation")
    StatusBarNotification decorate(List<NotificationCloudStore.Entry<StatusBarNotification>> group, NotificationCloudRules rules) {
        StatusBarNotification sbn = group.getFirst().value();
        StatusBarNotification rendered = sbn.clone();
        Notification n = rendered.getNotification();
        if (group.size() > 1) {
            Notification latest = group.get(group.size() - 1).value().getNotification();
            try { Reflect.call(Reflect.method(Notification.class, "cloneInto", void.class, Notification.class, boolean.class), latest, n, true); }
            catch (ReflectiveOperationException unavailable) { /* Keep the representative's native content if this optional copy is unavailable. */ }
        }
        n.extras = new Bundle(n.extras);
        if (group.size() > 1) {
            CharSequence title = n.extras.getCharSequence(Notification.EXTRA_TITLE, "通知");
            n.extras.putCharSequence(Notification.EXTRA_TITLE, title + " · " + group.size() + " 条");
        }
        var actions = new ArrayList<Notification.Action>();
        if (n.actions != null) for (Notification.Action item : n.actions) if (item != null && item.getRemoteInputs() == null && !item.isAuthenticationRequired()) {
            if (actions.size() < 2) actions.add(item);
        }
        var latest = group.getLast();
        if (rules.floating(latest.app()) && supportsFloating(latest.value())) {
            actions.add(new Notification.Action.Builder((Icon) null, "浮窗打开",
                    floatingButton(latest.key(), latest.revision(), latest.value().getNotification().contentIntent)).build());
        }
        n.actions = actions.toArray(Notification.Action[]::new);
        applyStyle(context, n, rules.style());
        removeDuplicateIcon(sbn.getPackageName(), n);
        return rendered;
    }
    private void removeDuplicateIcon(String pkg, Notification notification) {
        Icon image = notification.getLargeIcon();
        if (image == null) return;
        try {
            var pm = context.getPackageManager();
            Drawable picture = image.loadDrawable(context);
            if (picture == null) return;
            if (iconPreview == null) iconPreview = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888);
            if (comparisonPreview == null) comparisonPreview = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888);
            thumbnail(picture, iconPreview);
            Icon small = notification.getSmallIcon();
            Drawable mark = small == null ? null : small.loadDrawable(context);
            boolean duplicate = sameIcon(mark) || sameIcon(pm.getApplicationIcon(pkg));
            if (!duplicate) return;
            Reflect.field(Notification.class, "mLargeIcon", Icon.class).set(notification, null);
            notification.extras.remove(Notification.EXTRA_LARGE_ICON);
            notification.extras.remove(Notification.EXTRA_LARGE_ICON_BIG);
        } catch (ReflectiveOperationException | android.content.pm.PackageManager.NameNotFoundException | RuntimeException | LinkageError unavailable) {
            // An unresolved caller-supplied image can still be useful in the native card.
        }
    }
    private boolean sameIcon(Drawable candidate) {
        if (candidate == null) return false;
        thumbnail(candidate, comparisonPreview);
        return iconPreview.sameAs(comparisonPreview);
    }
    private static void thumbnail(Drawable drawable, Bitmap image) {
        image.eraseColor(android.graphics.Color.TRANSPARENT);
        var bounds = new android.graphics.Rect(drawable.getBounds());
        try { drawable.setBounds(0, 0, 32, 32); drawable.draw(new Canvas(image)); }
        finally { drawable.setBounds(bounds); }
    }
    static void applyStyle(Context context, Notification n, NotificationCloudRules.Style style) {
        if ((style == NotificationCloudRules.Style.AUTO || style == NotificationCloudRules.Style.PROGRESS)
                && (n.extras.getInt(Notification.EXTRA_PROGRESS_MAX) > 0 || n.extras.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE))) {
            try {
                int maximum = Math.max(1, n.extras.getInt(Notification.EXTRA_PROGRESS_MAX, 100));
                Notification.ProgressStyle progress = new Notification.ProgressStyle()
                        .setProgress(Math.max(0, Math.min(maximum, n.extras.getInt(Notification.EXTRA_PROGRESS))))
                        .setProgressIndeterminate(n.extras.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE))
                        .setStyledByProgress(false).addProgressSegment(new Notification.ProgressStyle.Segment(maximum));
                Notification styled = Notification.Builder.recoverBuilder(context, n).setStyle(progress).build();
                // recoverBuilder may return the supplied Notification itself. Self-cloning resets
                // its action array before reading it, leaving null actions in the native parser.
                if (styled != n) Reflect.call(Reflect.method(Notification.class, "cloneInto", void.class, Notification.class, boolean.class), styled, n, true);
            } catch (ReflectiveOperationException | RuntimeException | LinkageError unavailable) { /* Native generic template remains usable. */ }
        }
        if (style == NotificationCloudRules.Style.GLASS) n.extras.putString(Notification.EXTRA_TEMPLATE, "android.app.Notification$BigTextStyle");
        if (style == NotificationCloudRules.Style.COMPACT) {
            n.extras.remove(Notification.EXTRA_BIG_TEXT); n.extras.remove(Notification.EXTRA_PICTURE);
            CharSequence text = n.extras.getCharSequence(Notification.EXTRA_TEXT, "");
            if (text.length() > 120) n.extras.putCharSequence(Notification.EXTRA_TEXT, text.subSequence(0, 120) + "…");
            n.extras.putString(Notification.EXTRA_TEMPLATE, "");
        }
    }
    void more(String key, android.view.View anchor) {
        var group = feature.group(key);
        if (group.size() > 1) {
            List<String> labels = new ArrayList<>(); List<Runnable> operations = new ArrayList<>();
            for (var item : group) {
                Notification notification = item.value().getNotification();
                labels.add(notification.extras.getCharSequence(Notification.EXTRA_TITLE, "通知") + "\n" + notification.extras.getCharSequence(Notification.EXTRA_TEXT, ""));
                operations.add(() -> moreItem(item.key(), anchor));
            }
            showMenu(anchor, "本组 " + group.size() + " 条通知", labels, operations); return;
        }
        moreItem(key, anchor);
    }
    List<MenuAction> nativeMenu(String key) {
        var group = feature.group(key);
        if (group.isEmpty()) return List.of();
        var result = new ArrayList<MenuAction>();
        if (group.size() > 1) {
            result.add(new MenuAction("查看本组 " + group.size() + " 条通知", () -> more(key, null)));
        }
        result.addAll(itemActions(group.get(group.size() - 1).key()));
        return result;
    }
    void moreItem(String key, android.view.View anchor) {
        var menu = itemActions(key);
        if (menu.isEmpty()) return;
        showMenu(anchor, "通知操作", menu.stream().map(MenuAction::label).toList(), menu.stream().map(MenuAction::run).toList());
    }
    private List<MenuAction> itemActions(String key) {
        var entry = feature.store.get(key); if (entry == null) return List.of();
        Notification n = entry.value().getNotification();
        var items = new ArrayList<MenuAction>();
        if (n.contentIntent != null) items.add(new MenuAction("打开这条通知", () -> { if (!feature.open(key)) feature.original(key); }));
        if (supportsFloating(entry.value())) items.add(new MenuAction("浮窗打开", () -> openFloating(key)));
        if (n.actions != null) for (Notification.Action button : n.actions) if (button != null && button.actionIntent != null) {
            items.add(new MenuAction(button.title == null ? "通知操作" : button.title.toString(), () -> {
                if (button.getRemoteInputs() != null || button.isAuthenticationRequired()) feature.original(key);
                else try { button.actionIntent.send(); } catch (PendingIntent.CanceledException failure) { feature.original(key); }
            }));
        }
        items.add(new MenuAction("移回通知中心", () -> feature.original(key)));
        if (n.actions != null && java.util.Arrays.stream(n.actions).anyMatch(button -> button != null && button.getRemoteInputs() != null))
            items.add(new MenuAction("查看原通知与快捷回复", () -> feature.original(key)));
        if (entry.value().isClearable()) {
            items.add(new MenuAction("清除这条通知", () -> dismiss(key)));
        }
        return items;
    }
    private boolean supportsFloating(StatusBarNotification notification) {
        if (floating == null) return false;
        try { return floating.supports(notification); }
        catch (RuntimeException | LinkageError unavailable) {
            feature.failure("ColorOS notification floating capability unavailable", unavailable);
            return false;
        }
    }

    private void openFloating(String key) {
        if (!feature.enabled() || !feature.unlocked() || !feature.store.ready(key)) return;
        var item = feature.store.get(key);
        Object live = feature.liveEntry(key);
        if (live == null || feature.notification(live) != item.value()) return;
        boolean opened = false;
        if (supportsFloating(item.value())) try {
            feature.armOpen(key, item.value().getNotification().contentIntent);
            opened = floating.open(item.value());
        } catch (RuntimeException | LinkageError unavailable) {
            feature.failure("ColorOS notification floating launch unavailable", unavailable);
        }
        if (!opened) {
            Toast.makeText(context, "无法以浮窗打开这条通知", Toast.LENGTH_SHORT).show();
            return;
        }
        feature.closeCard(() -> { });
        try {
            Object status = context.getSystemService(Class.forName("android.app.StatusBarManager"));
            if (status != null) Reflect.call(Reflect.method(status.getClass(), "collapsePanels", void.class), status);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError unavailable) {
            feature.failure("Notification shade could not close after floating launch", unavailable);
        }
    }
    private void showMenu(android.view.View anchor, String title, List<String> labels, List<Runnable> operations) {
        if (anchor != null && anchor.isAttachedToWindow() && feature.unlocked()) {
            android.widget.PopupMenu menu = new android.widget.PopupMenu(anchor.getContext(), anchor);
            for (int i = 0; i < labels.size(); i++) menu.getMenu().add(0, i, i, labels.get(i));
            menu.setOnMenuItemClickListener(item -> {
                if (feature.enabled() && feature.unlocked()) operations.get(item.getItemId()).run();
                return true;
            });
            menu.show();
        } else feature.closeCard(() -> showClosedMenu(title, labels, operations));
    }
    private void showClosedMenu(String title, List<String> labels, List<Runnable> operations) {
        if (!feature.enabled() || !feature.unlocked()) return;
        try {
            Class<?> type = Class.forName("com.android.systemui.statusbar.phone.SystemUIDialog", false, context.getClassLoader());
            AlertDialog dialog = (AlertDialog) type.getDeclaredConstructor(Context.class).newInstance(context);
            dialog.setTitle(title);
            android.widget.ListView list = new android.widget.ListView(context);
            list.setAdapter(new android.widget.ArrayAdapter<>(context, android.R.layout.simple_list_item_1, labels));
            list.setOnItemClickListener((parent, view, index, id) -> { dialog.dismiss(); if (feature.unlocked()) operations.get(index).run(); });
            // ListView's wrap-content measurement otherwise exposes only the first row.
            int height = Math.min(Math.round(labels.size() * 64 * context.getResources().getDisplayMetrics().density),
                    Math.round(context.getResources().getDisplayMetrics().heightPixels * .6f));
            android.widget.FrameLayout content = new android.widget.FrameLayout(context);
            content.addView(list, new android.widget.FrameLayout.LayoutParams(android.view.ViewGroup.LayoutParams.MATCH_PARENT, height));
            dialog.setView(content);
            dialog.show();
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
            feature.failure("Notification actions dialog unavailable; native rows retained", failure);
        }
    }
    void showOriginal(String key) {
        feature.closeCard(() -> showOriginalRow(key));
    }
    private void showOriginalRow(String key) {
        try {
            Object status = context.getSystemService(Class.forName("android.app.StatusBarManager"));
            if (status != null) Reflect.call(Reflect.method(status.getClass(), "expandNotificationsPanel", void.class), status);
            Object entry = feature.originalEntry(key);
            if (entry != null) {
                Object row = Reflect.call(Reflect.uniqueMethod(entry.getClass(), method -> method.getName().equals("getRow") && method.getParameterCount() == 0), entry);
                if (row != null) Reflect.call(Reflect.method(row.getClass(), "setUserExpanded", void.class, boolean.class, boolean.class), row, true, true);
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
            feature.failure("Native notification navigation unavailable", failure);
        }
    }
    private void dismiss(String key) {
        if (!feature.dismissOriginal(key)) feature.original(key);
    }
}
