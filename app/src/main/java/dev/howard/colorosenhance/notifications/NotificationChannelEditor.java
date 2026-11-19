package dev.howard.colorosenhance.notifications;

import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.*;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Edits one application's list membership, interaction options and channel overrides. */
final class NotificationChannelEditor extends ScrollView {
    private static final List<String> MODES = List.of("不转换，保留原厂", "仅胶囊", "胶囊并自动展开");
    private final NotificationCloudRules original;
    private final String pkg;
    private final Map<String, NotificationCloudRules.Mode> overrides = new LinkedHashMap<>();
    private final LinearLayout body, existing;
    private final EditText manual;
    private final CheckBox allowed, denied;
    private final Spinner channels, mode, appMode, floating;
    private final TextView status;
    private final List<String> ids = new ArrayList<>();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final int color;
    private int request;
    private String defaultStatus;

    NotificationChannelEditor(Context context, NotificationCloudRules rules, String pkg, String label, Runnable back) {
        super(context);
        original = rules;
        this.pkg = pkg;
        rules.overrides().forEach((key, value) -> {
            if (key.startsWith(pkg + "/")) overrides.put(key, value);
        });
        body = new LinearLayout(context);
        body.setOrientation(LinearLayout.VERTICAL);
        int padding = Math.round(12 * getResources().getDisplayMetrics().density);
        body.setPadding(padding, padding, padding, padding);
        addView(body);
        color = NotificationAppPicker.textColor(context);
        button("‹ 返回应用列表", back);
        text(label).setTextSize(17);
        allowed = check("加入白名单", rules.allowed().contains(pkg));
        denied = check("加入黑名单", rules.denied().contains(pkg));
        text("当前使用" + (rules.denylist() ? "黑名单" : "白名单") + "模式。只有符合名单规则的通知才会转换。");
        floating = select("在卡片显示浮窗按钮", List.of("跟随全局", "开启", "关闭"));
        var override = rules.appFloating().get(pkg);
        floating.setSelection(override == null ? 0 : override.ordinal() + 1);
        List<String> defaults = new ArrayList<>(List.of("按通知种类"));
        defaults.addAll(MODES);
        appMode = select("应用默认转换方式", defaults);
        var type = rules.overrides().get(pkg);
        appMode.setSelection(type == null ? 0 : type.ordinal() + 1);
        text("渠道设置优先于应用默认。可选择实际渠道，或手动输入 ID。");
        button("查询／刷新通知渠道", this::query);
        defaultStatus = "正在查询通知渠道…";
        status = text(defaultStatus);
        channels = select(null, List.of("选择渠道…"));
        setChannels(new Bundle());
        manual = new EditText(context);
        manual.setSingleLine(true); manual.setHint("手动渠道 ID"); manual.setTextColor(color);
        manual.setHintTextColor(0x99000000 | (color & 0x00ffffff));
        body.addView(manual);
        mode = select("渠道转换方式", MODES);
        mode.setSelection(1);
        button("添加／更新渠道规则", this::saveChannel);
        existing = new LinearLayout(context);
        existing.setOrientation(LinearLayout.VERTICAL);
        body.addView(existing);
        render();
    }

    @Override protected void onAttachedToWindow() { super.onAttachedToWindow(); query(); }
    @Override protected void onDetachedFromWindow() { request++; super.onDetachedFromWindow(); }

    private void saveChannel() {
        String channel = manual.getText().toString().trim();
        if (channel.isEmpty() && channels.getSelectedItemPosition() > 0) channel = ids.get(channels.getSelectedItemPosition());
        if (channel.isEmpty() || pkg.length() + channel.length() > 590) {
            manual.setError(channel.isEmpty() ? "请选择或填写渠道 ID" : "渠道 ID 过长");
            return;
        }
        overrides.put(pkg + "/" + channel, NotificationCloudRules.Mode.values()[mode.getSelectedItemPosition()]);
        manual.setText("");
        status.setText("已添加规则，保存后生效。");
        render();
    }
    private TextView text(String value) {
        TextView view = new TextView(getContext());
        view.setText(value); view.setTextSize(14); view.setTextColor(color); view.setPadding(0, 12, 0, 12);
        body.addView(view);
        return view;
    }
    private CheckBox check(String label, boolean checked) {
        CheckBox view = new CheckBox(getContext());
        view.setText(label); view.setTextColor(color); view.setChecked(checked);
        body.addView(view);
        return view;
    }
    private void button(String label, Runnable action) {
        Button view = new Button(getContext());
        view.setText(label); view.setOnClickListener(ignored -> action.run());
        body.addView(view);
    }
    private Spinner select(String title, List<String> labels) {
        if (title != null) text(title);
        Spinner list = new Spinner(getContext());
        list.setAdapter(choices(labels));
        body.addView(list);
        return list;
    }
    private ArrayAdapter<String> choices(List<String> labels) {
        return new ArrayAdapter<>(getContext(), android.R.layout.simple_spinner_dropdown_item, labels) {
            private View style(View view) { ((TextView) view).setTextColor(color); return view; }
            @Override public View getView(int position, View recycled, ViewGroup parent) { return style(super.getView(position, recycled, parent)); }
            @Override public View getDropDownView(int position, View recycled, ViewGroup parent) { return style(super.getDropDownView(position, recycled, parent)); }
        };
    }
    private void query() {
        int generation = ++request;
        status.setText(defaultStatus);
        Bundle args = new Bundle();
        args.putString("package", pkg);
        fetch(generation, args, 0);
    }
    private void fetch(int generation, Bundle args, int attempt) {
        new Thread(() -> {
            Bundle response = null;
            try {
                response = getContext().getContentResolver().call(NotificationMetadataProvider.URI, attempt == 0 ? "request" : "read", null, args);
            } catch (RuntimeException unavailable) { /* Show a usable manual-entry fallback. */ }
            Bundle result = response;
            main.post(() -> {
                if (generation != request || !isAttachedToWindow()) return;
                if (result != null && result.getBoolean("ready")) {
                    setChannels(result);
                    status.setText(ids.size() == 1 ? "尚未查到渠道，可让应用先发一条通知再刷新。"
                            : result.getString("source", "已查到通知渠道"));
                } else if (result == null || attempt >= 12) {
                    status.setText("未收到渠道列表。确认系统界面作用域已加载后刷新，也可手动输入。");
                } else main.postDelayed(() -> {
                    if (generation == request && isAttachedToWindow()) fetch(generation, args, attempt + 1);
                }, 300);
            });
        }, "coloros-notification-channels").start();
    }
    private void setChannels(Bundle result) {
        ids.clear(); ids.add("");
        List<String> labels = new ArrayList<>(List.of("选择渠道…"));
        String[] keys = result.getStringArray("ids");
        String[] names = result.getStringArray("labels");
        if (keys != null && names != null) {
            for (int i = 0; i < Math.min(keys.length, names.length); i++) {
                ids.add(keys[i]);
                labels.add(names[i] + " · " + keys[i]);
            }
        }
        channels.setAdapter(choices(labels));
    }
    private void render() {
        existing.removeAllViews();
        for (var item : overrides.entrySet()) {
            Button view = new Button(getContext());
            view.setText(item.getKey().substring(pkg.length() + 1) + "\n" + NotificationCloudRules.label(item.getValue()) + " · 移除");
            view.setOnClickListener(ignored -> { overrides.remove(item.getKey()); render(); });
            existing.addView(view);
        }
    }
    NotificationCloudRules value() {
        var values = new LinkedHashMap<>(overrides);
        int position = appMode.getSelectedItemPosition();
        if (position > 0) values.put(pkg, NotificationCloudRules.Mode.values()[position - 1]);
        int floatMode = floating.getSelectedItemPosition();
        return original.editApp(pkg, allowed.isChecked(), denied.isChecked(),
                floatMode <= 0 ? null : NotificationCloudRules.Floating.values()[floatMode - 1], values);
    }
}
