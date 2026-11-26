package dev.howard.colorosenhance.notifications;

import android.content.Context;
import android.os.Bundle;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.List;

/** Keeps all edited instances in one draft until the application settings page is saved. */
final class NotificationAppEditor extends LinearLayout {
    private record Instance(int serial, String label) { }
    private final String pkg;
    private final Spinner selector;
    private List<Instance> instances = List.of(new Instance(NotificationCloudRules.MAIN_APP, "本体"));
    private NotificationCloudRules draft;
    private NotificationChannelEditor editor;
    private int serial = NotificationCloudRules.MAIN_APP;

    NotificationAppEditor(Context context, NotificationCloudRules rules, String pkg, String label, Runnable back) {
        super(context);
        draft = rules;
        this.pkg = pkg;
        setOrientation(VERTICAL);
        Button backButton = new Button(context);
        backButton.setText("‹ 返回应用列表");
        backButton.setOnClickListener(view -> back.run());
        addView(backButton);
        TextView title = new TextView(context);
        title.setText(label);
        title.setTextSize(17);
        title.setTextColor(NotificationAppPicker.textColor(context));
        addView(title);
        selector = new Spinner(context);
        selector.setVisibility(GONE);
        selector.setContentDescription("应用实例");
        addView(selector);
        selector.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (position >= 0 && position < instances.size()) show(instances.get(position).serial());
            }
            @Override public void onNothingSelected(AdapterView<?> parent) { }
        });
        show(serial);
    }

    private void updateInstances(Bundle result) {
        int[] serials = result.getIntArray("clone_serials");
        String[] names = result.getStringArray("clone_labels");
        List<Instance> next = new ArrayList<>(List.of(new Instance(NotificationCloudRules.MAIN_APP, "本体")));
        if (serials != null && names != null && serials.length == names.length)
            for (int i = 0; i < serials.length; i++)
                if (serials[i] >= 0 && names[i] != null) next.add(new Instance(serials[i], names[i]));
        if (next.equals(instances)) return;
        instances = List.copyOf(next);
        selector.setAdapter(new ArrayAdapter<>(getContext(), android.R.layout.simple_spinner_dropdown_item,
                instances.stream().map(Instance::label).toList()) {
            @Override public View getView(int position, View recycled, android.view.ViewGroup parent) {
                TextView text = (TextView) super.getView(position, recycled, parent);
                text.setTextColor(NotificationAppPicker.textColor(getContext()));
                return text;
            }
            @Override public View getDropDownView(int position, View recycled, android.view.ViewGroup parent) {
                TextView text = (TextView) super.getDropDownView(position, recycled, parent);
                text.setTextColor(NotificationAppPicker.textColor(getContext()));
                return text;
            }
        });
        selector.setVisibility(instances.size() > 1 ? VISIBLE : GONE);
    }

    private void show(int next) {
        if (editor != null) {
            if (serial == next) return;
            draft = editor.value();
            removeView(editor);
        }
        serial = next;
        editor = new NotificationChannelEditor(getContext(), draft, pkg, serial, this::updateInstances);
        addView(editor, new LayoutParams(-1, 0, 1f));
    }

    NotificationCloudRules value() { return editor == null ? draft : editor.value(); }
}
