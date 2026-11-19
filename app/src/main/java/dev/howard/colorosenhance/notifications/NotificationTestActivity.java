package dev.howard.colorosenhance.notifications;

import android.Manifest;
import android.app.Activity;
import android.os.Bundle;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

public final class NotificationTestActivity extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        var body = new LinearLayout(this); body.setOrientation(LinearLayout.VERTICAL);
        int padding = Math.round(24 * getResources().getDisplayMetrics().density); body.setPadding(padding, padding * 2, padding, padding);
        TextView label = new TextView(this); label.setText("通知转流体云自测\n先把 ColorOS 增强加入白名单并开启通知转换。测试通知不包含个人数据。\n"
                + "打开目标：" + getIntent().getIntExtra("notification_id", -1)); body.addView(label);
        button(body, "发送进度通知", () -> NotificationTestReceiver.post(this, NotificationTestReceiver.ID, 35, false, true));
        button(body, "发送普通通知", () -> NotificationTestReceiver.post(this, NotificationTestReceiver.ID + 1, 0, false, false));
        button(body, "发送静默通知", () -> NotificationTestReceiver.post(this, NotificationTestReceiver.ID + 2, 0, true, false));
        button(body, "更新同一通知", () -> NotificationTestReceiver.post(this, NotificationTestReceiver.ID, 75, false, true));
        button(body, "发送六条普通通知", () -> { for (int i = 4; i < 10; i++) NotificationTestReceiver.post(this, NotificationTestReceiver.ID + i, 0, false, false); });
        button(body, "清除本轮测试通知", () -> NotificationTestReceiver.clear(this));
        button(body, "发送 30 条通知（容量测试）", () -> { for (int i = 4; i < 34; i++) NotificationTestReceiver.post(this, NotificationTestReceiver.ID + i, 0, false, false); });
        button(body, "发送高优先级测试通知", () -> NotificationTestReceiver.post(this, NotificationTestReceiver.ID + 3, 0, false, false, true));
        setContentView(body);
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 31);
    }
    private void button(LinearLayout body, String title, Runnable action) { Button button = new Button(this); button.setText(title); button.setOnClickListener(view -> action.run()); body.addView(button); }
}
