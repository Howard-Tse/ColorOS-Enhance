package dev.howard.colorosenhance.notifications;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Color;
import android.text.Editable;
import android.text.TextWatcher;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import org.json.JSONArray;

public final class NotificationAppPicker extends LinearLayout {
    private final NotificationCloudRules original;
    private final Set<String> selected;
    private final Set<String> savedSelection;
    private final Map<String, String> apps = new LinkedHashMap<>();
    private final android.widget.ListView rows;
    private final java.util.List<String> visible = new java.util.ArrayList<>();
    private final android.widget.BaseAdapter adapter;
    private final EditText search;
    private final ProgressBar progress;
    private final TextView empty;
    private final LinearLayout appList;
    private final boolean details;
    private final int textColor;
    private final android.window.OnBackInvokedCallback back = this::backToApps;
    private android.window.OnBackInvokedDispatcher dispatcher;
    private NotificationCloudRules edited;
    private NotificationAppEditor editor;
    private boolean loaded;
    private int loadGeneration;
    public NotificationAppPicker(Context context, NotificationCloudRules rules) {
        this(context, rules, false);
    }
    public NotificationAppPicker(Context context, NotificationCloudRules rules, boolean details) {
        super(context); original = rules; edited = rules; this.details = details;
        savedSelection = rules.denylist() ? rules.denied() : rules.allowed();
        selected = new LinkedHashSet<>(savedSelection);
        textColor = textColor(context);
        setOrientation(VERTICAL); int padding = Math.round(12 * getResources().getDisplayMetrics().density); setPadding(padding, padding, padding, padding);
        appList = new LinearLayout(context);
        appList.setOrientation(VERTICAL);
        addView(appList, new LayoutParams(-1, details ? -1 : -2));
        search = new EditText(context); search.setSingleLine(true); search.setHint("搜索应用名称或包名");
        search.setTextColor(textColor); search.setHintTextColor(0x99000000 | (textColor & 0x00ffffff)); appList.addView(search);
        progress = new ProgressBar(context);
        LayoutParams indicator = new LayoutParams(padding * 3, padding * 3);
        indicator.gravity = android.view.Gravity.CENTER;
        appList.addView(progress, indicator);
        empty = new TextView(context);
        empty.setTextColor(textColor);
        empty.setVisibility(GONE);
        appList.addView(empty);
        rows = new android.widget.ListView(context);
        adapter = new android.widget.BaseAdapter() {
            public int getCount() { return visible.size(); }
            public Object getItem(int position) { return visible.get(position); }
            public long getItemId(int position) { return visible.get(position).hashCode(); }
            @Override public android.view.View getView(int position, android.view.View recycled, android.view.ViewGroup parent) {
                String pkg = visible.get(position);
                TextView label = recycled instanceof TextView existing ? existing : details ? new TextView(context) : new CheckBox(context);
                label.setText(apps.get(pkg)); label.setTextSize(14); label.setTextColor(textColor);
                label.setGravity(android.view.Gravity.CENTER_VERTICAL);
                label.setMinHeight(Math.round(52 * getResources().getDisplayMetrics().density));
                if (details) return label;
                CheckBox box = (CheckBox) label;
                box.setOnCheckedChangeListener(null);
                box.setChecked(selected.contains(pkg));
                box.setOnCheckedChangeListener((ignored, checked) -> { if (checked) selected.add(pkg); else selected.remove(pkg); });
                return box;
            }
        };
        rows.setAdapter(adapter);
        rows.setVisibility(GONE);
        appList.addView(rows, details ? new LayoutParams(-1, 0, 1f)
                : new LayoutParams(-1, Math.round(300 * getResources().getDisplayMetrics().density)));
        if (details) rows.setOnItemClickListener((list, row, position, id) -> showDetails(visible.get(position)));
        search.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s,int start,int count,int after) { }
            public void onTextChanged(CharSequence s,int start,int before,int count) { render(s.toString()); }
            public void afterTextChanged(Editable value) { }
        });
    }
    static int textColor(Context context) {
        boolean dark = (context.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
        int themed = new TextView(context).getCurrentTextColor();
        if (dark && Color.luminance(themed) < .4f) return 0xffeeeeee;
        if (!dark && Color.luminance(themed) > .5f) return 0xff202124;
        return themed;
    }
    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        registerBack();
        if (loaded) return;
        int generation = ++loadGeneration;
        Context context = getContext().getApplicationContext();
        if (context == null) context = getContext();
        Context lookup = context;
        new Thread(() -> {
            var found = new java.util.TreeMap<String, String>(java.text.Collator.getInstance());
            boolean failed = false;
            try {
                var manager = lookup.getPackageManager();
                for (var item : manager.getInstalledApplications(0)) {
                    String pkg = item.packageName;
                    if (NotificationCloudRules.validPackage(pkg))
                        found.put(manager.getApplicationLabel(item) + " · " + pkg, pkg);
                }
            } catch (RuntimeException unavailable) { failed = true; }
            boolean queryFailed = failed;
            post(() -> {
                if (generation != loadGeneration || !isAttachedToWindow()) return;
                found.forEach((label, pkg) -> apps.put(pkg, label));
                for (String pkg : original.allowed()) apps.putIfAbsent(pkg, pkg);
                for (String pkg : original.denied()) apps.putIfAbsent(pkg, pkg);
                for (String key : original.overrides().keySet()) {
                    String pkg = key.split("/", 2)[0];
                    apps.putIfAbsent(pkg, pkg);
                }
                for (String pkg : original.appFloating().keySet()) apps.putIfAbsent(pkg, pkg);
                loaded = true;
                progress.setVisibility(GONE);
                rows.setVisibility(VISIBLE);
                if (queryFailed && apps.isEmpty()) empty.setText("暂时无法读取应用列表，请检查系统的应用列表访问权限");
                else empty.setText("没有匹配的应用");
                render(search.getText().toString());
            });
        }, "coloros-notification-apps").start();
    }
    @Override protected void onDetachedFromWindow() {
        loadGeneration++;
        unregisterBack();
        super.onDetachedFromWindow();
    }
    private void showDetails(String pkg) {
        search.clearFocus();
        var keyboard = getContext().getSystemService(android.view.inputmethod.InputMethodManager.class);
        if (keyboard != null) keyboard.hideSoftInputFromWindow(search.getWindowToken(), 0);
        appList.setVisibility(GONE);
        editor = new NotificationAppEditor(getContext(), edited, pkg, apps.get(pkg), this::backToApps);
        addView(editor, new LayoutParams(-1, -1));
        registerBack();
    }
    public boolean backToApps() {
        if (editor == null) return false;
        edited = editor.value();
        removeView(editor);
        editor = null;
        unregisterBack();
        appList.setVisibility(VISIBLE);
        return true;
    }
    private void registerBack() {
        if (editor == null || dispatcher != null) return;
        dispatcher = findOnBackInvokedDispatcher();
        if (dispatcher != null) dispatcher.registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_OVERLAY, back);
    }
    private void unregisterBack() {
        if (dispatcher == null) return;
        dispatcher.unregisterOnBackInvokedCallback(back);
        dispatcher = null;
    }
    private void render(String query) {
        visible.clear(); String filter = query.toLowerCase(java.util.Locale.ROOT);
        apps.forEach((pkg, label) -> {
            if (!label.toLowerCase(java.util.Locale.ROOT).contains(filter)) return;
            visible.add(pkg);
        });
        if (!details) visible.sort(java.util.Comparator.comparing(pkg -> !savedSelection.contains(pkg)));
        adapter.notifyDataSetChanged();
        empty.setVisibility(loaded && visible.isEmpty() ? VISIBLE : GONE);
    }
    public String encodedValue() {
        if (details) return (editor == null ? edited : editor.value()).encode();
        return original.with(original.denylist() ? "denied" : "allowed", new JSONArray(selected.stream().sorted().toList())).encode();
    }
}
