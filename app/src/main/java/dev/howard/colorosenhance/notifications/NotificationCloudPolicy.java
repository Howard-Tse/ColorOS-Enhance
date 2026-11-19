package dev.howard.colorosenhance.notifications;

import android.app.Notification;
import android.app.NotificationManager;
import android.os.UserHandle;
import android.service.notification.StatusBarNotification;

public final class NotificationCloudPolicy {
    public static NotificationCloudRules.Mode evaluate(StatusBarNotification sbn, UserHandle user, int importance,
            int channelImportance, boolean suspended, NotificationCloudRules rules) {
        if (!rules.accepts(sbn.getPackageName())) return NotificationCloudRules.Mode.NATIVE;
        Notification n = sbn.getNotification();
        if (!sbn.getUser().equals(user) || suspended || importance == NotificationManager.IMPORTANCE_NONE
                || n == null || n.getSmallIcon() == null || n.fullScreenIntent != null
                || (n.flags & Notification.FLAG_GROUP_SUMMARY) != 0) return NotificationCloudRules.Mode.NATIVE;
        String pkg = sbn.getPackageName();
        if (pkg.equals("android") || pkg.equals("com.android.systemui") || pkg.equals("com.android.phone") || pkg.equals("com.android.incallui")
                || Notification.CATEGORY_ALARM.equals(n.category) || Notification.CATEGORY_SYSTEM.equals(n.category)
                || Notification.CATEGORY_TRANSPORT.equals(n.category)) return NotificationCloudRules.Mode.NATIVE;
        if (n.extras != null) {
            for (String key : n.extras.keySet()) if (key.startsWith("oplus.livealert") || key.startsWith("oplus.aod")) return NotificationCloudRules.Mode.NATIVE;
            if (n.extras.getString(Notification.EXTRA_TEMPLATE, "").contains("MediaStyle")) return NotificationCloudRules.Mode.NATIVE;
        }
        boolean progress = n.extras != null && (n.extras.getInt(Notification.EXTRA_PROGRESS_MAX) > 0
                || n.extras.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE));
        return rules.mode(pkg, n.getChannelId(), kind(Notification.CATEGORY_CALL.equals(n.category), importance, channelImportance, sbn.isOngoing(), progress));
    }
    public static NotificationCloudRules.Kind kind(boolean call, int importance, int channelImportance, boolean ongoing, boolean progress) {
        if (importance <= NotificationManager.IMPORTANCE_LOW || channelImportance != NotificationManager.IMPORTANCE_UNSPECIFIED
                && channelImportance <= NotificationManager.IMPORTANCE_LOW) return NotificationCloudRules.Kind.SILENT;
        if (call) return NotificationCloudRules.Kind.CALL;
        return ongoing || progress ? NotificationCloudRules.Kind.ONGOING : NotificationCloudRules.Kind.ORDINARY;
    }
    private NotificationCloudPolicy() { }
}
