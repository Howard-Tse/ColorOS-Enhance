package dev.howard.colorosenhance.notifications;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import dev.howard.colorosenhance.R;

/** A safe, module-owned notification for acceptance; never sends messages or touches other apps. */
public final class NotificationTestReceiver extends BroadcastReceiver {
    public static final String CHANNEL = "cloud_test";
    static final String QUIET = "cloud_test_quiet";
    static final String ALERT = "cloud_test_alerting";
    static final int ID = 7290;
    @Override public void onReceive(Context context, Intent intent) {
        int id = intent.getIntExtra("id", ID);
        if (id < ID || id > ID + 33) return;
        if (intent.getBooleanExtra("cancel", false)) context.getSystemService(NotificationManager.class).cancel(id);
        else post(context, id, intent.getIntExtra("progress", 65), intent.getBooleanExtra("quiet", false), intent.getBooleanExtra("ongoing", true));
    }
    public static void post(Context context, int id, int progress, boolean quiet, boolean ongoing) {
        post(context, id, progress, quiet, ongoing, false);
    }
    public static void post(Context context, int id, int progress, boolean quiet, boolean ongoing, boolean headsUp) {
        var manager = context.getSystemService(NotificationManager.class);
        var channel = headsUp ? new NotificationChannel(ALERT, "流体云横幅测试", NotificationManager.IMPORTANCE_HIGH)
                : new NotificationChannel(quiet ? QUIET : CHANNEL, quiet ? "流体云静默测试" : "流体云功能测试",
                        quiet ? NotificationManager.IMPORTANCE_LOW : NotificationManager.IMPORTANCE_DEFAULT);
        if (headsUp) channel.enableVibration(true);
        else { channel.setSound(null, null); channel.enableVibration(false); }
        manager.createNotificationChannel(channel);
        Intent target = new Intent(context, NotificationTestActivity.class).putExtra("notification_id", id);
        PendingIntent open = PendingIntent.getActivity(context, id, target, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        PendingIntent advance = PendingIntent.getBroadcast(context, id, new Intent(context, NotificationTestReceiver.class)
                .setAction("advance." + id).putExtra("id", id).putExtra("progress", 85), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        PendingIntent finish = PendingIntent.getBroadcast(context, id, new Intent(context, NotificationTestReceiver.class)
                .setAction("finish." + id).putExtra("id", id).putExtra("cancel", true), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder builder = new Notification.Builder(context, channel.getId()).setSmallIcon(R.drawable.ic_notification_cloud)
                .setContentTitle("通知转流体云测试 " + (id - ID + 1)).setContentText("模块自测内容 · 点击打开测试页")
                .setContentIntent(open).setOnlyAlertOnce(true).setOngoing(ongoing)
                .addAction(new Notification.Action.Builder(null, "更新进度", advance).build())
                .addAction(new Notification.Action.Builder(null, "结束测试", finish).build());
        if (ongoing) builder.setProgress(100, progress, false);
        manager.notify(id, builder.build());
    }
    public static void clear(Context context) {
        var manager = context.getSystemService(NotificationManager.class);
        for (int id = ID; id <= ID + 33; id++) manager.cancel(id);
    }
}
