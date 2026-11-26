package dev.howard.colorosenhance.notifications;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** User policy only. Notification titles, text and PendingIntents never enter this format. */
public record NotificationCloudRules(boolean denylist, Set<String> allowed, Set<String> denied,
        Mode ongoing, Mode ordinary, Mode silent, Mode calls, Original original, Style style,
        boolean groupApps, boolean directOpen, boolean floating, Map<String, Mode> overrides,
        Map<String, Floating> appFloating, CapsuleText capsuleText, AfterOpen afterOpen,
        boolean keepAlerts, boolean shadeCards, boolean retainOnLock, boolean lockCards, Map<String, CloneRule> clones,
        boolean swipeDismiss, boolean history, boolean suppressOriginalBanner) {
    public static final String KEY = "notification_cloud_rules";
    public static final String ENABLED = "notification_cloud_enabled";
    public enum Mode { NATIVE, CAPSULE, EXPAND }
    public enum Original { KEEP, COLLECT, HIDE }
    public enum Style { AUTO, GLASS, COMPACT, PROGRESS }
    public enum Kind { ONGOING, ORDINARY, SILENT, CALL }
    public enum Floating { ON, OFF }
    public enum CapsuleText { CONTENT, APP }
    public enum AfterOpen { KEEP, CARD, BOTH }
    public static final int MAIN_APP = -1;
    public record AppRule(boolean allowed, boolean denied, Floating floating, Mode mode, Map<String, Mode> channels) {
        public AppRule { channels = Map.copyOf(channels); }
    }
    public record CloneRule(boolean inherit, AppRule app) { }
    public static final NotificationCloudRules DEFAULT = new NotificationCloudRules(false, Set.of(), Set.of(),
            Mode.CAPSULE, Mode.CAPSULE, Mode.NATIVE, Mode.NATIVE, Original.COLLECT, Style.AUTO,
            true, false, false, Map.of(), Map.of(), CapsuleText.CONTENT, AfterOpen.KEEP, true, false, false, false, Map.of(), true, false, false);

    public NotificationCloudRules {
        allowed = Set.copyOf(allowed); denied = Set.copyOf(denied); overrides = Map.copyOf(overrides);
        appFloating = Map.copyOf(appFloating); clones = Map.copyOf(clones);
    }
    public CloneRule cloneRule(String pkg, int serial) { return serial < 0 ? null : clones.get(serial + ":" + pkg); }
    private AppRule independentRule(String pkg, int serial) {
        CloneRule clone = cloneRule(pkg, serial);
        return clone == null || clone.inherit() ? null : clone.app();
    }
    public boolean accepts(String pkg) { return accepts(pkg, MAIN_APP); }
    public boolean accepts(String pkg, int serial) {
        AppRule rule = independentRule(pkg, serial);
        if (rule == null) return denylist ? !denied.contains(pkg) : allowed.contains(pkg);
        return denylist ? !rule.denied() : rule.allowed();
    }
    public boolean floating(String pkg) { return floating(pkg, MAIN_APP); }
    public boolean floating(String pkg, int serial) {
        AppRule rule = independentRule(pkg, serial);
        Floating override = rule == null ? appFloating.get(pkg) : rule.floating();
        return override == null ? floating : override == Floating.ON;
    }
    public boolean hideOriginal() { return original != Original.KEEP; }
    public boolean replaceHeadsUp() { return hideOriginal() || suppressOriginalBanner; }
    public Mode mode(String pkg, String channel, Kind kind) {
        return mode(pkg, MAIN_APP, channel, kind);
    }
    public Mode mode(String pkg, int serial, String channel, Kind kind) {
        if (!accepts(pkg, serial)) return Mode.NATIVE;
        AppRule rule = independentRule(pkg, serial);
        Mode value = rule == null ? overrides.get(pkg + "/" + channel) : channel == null ? null : rule.channels().get(channel);
        if (value == null) value = rule == null ? overrides.get(pkg) : rule.mode();
        if (value != null) return value;
        return switch (kind) { case ONGOING -> ongoing; case ORDINARY -> ordinary; case SILENT -> silent; case CALL -> calls; };
    }
    public static NotificationCloudRules from(Map<String, ?> values) {
        Object encoded = values.get(KEY);
        NotificationCloudRules parsed = encoded instanceof String text ? parse(text) : null;
        return parsed == null ? DEFAULT : parsed;
    }
    public static boolean validPackage(String pkg) {
        return pkg != null && pkg.length() <= 220 && pkg.matches("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+");
    }
    public static NotificationCloudRules parse(String text) {
        if (text == null || text.length() > 16384) return null;
        try {
            JSONObject o = new JSONObject(text);
            if (o.getInt("schema") != 1) return null;
            Map<String, Mode> overrides = new LinkedHashMap<>();
            JSONObject rules = o.optJSONObject("overrides");
            if (rules != null) {
                var keys = rules.keys();
                while (keys.hasNext()) {
                    String key = keys.next(); int separator = key.indexOf('/');
                    String pkg = separator < 0 ? key : key.substring(0, separator);
                    if (!validPackage(pkg) || key.length() > 600 || separator == key.length() - 1) return null;
                    overrides.put(key, Mode.valueOf(rules.getString(key)));
                }
            }
            return new NotificationCloudRules(o.optBoolean("denylist", false), packages(o.optJSONArray("allowed")),
                    packages(o.optJSONArray("denied")), Mode.valueOf(o.optString("ongoing", "CAPSULE")),
                    Mode.valueOf(o.optString("ordinary", "CAPSULE")), Mode.valueOf(o.optString("silent", "NATIVE")),
                    Mode.valueOf(o.optString("calls", "NATIVE")), Original.valueOf(o.optString("original", "COLLECT")),
                    Style.valueOf(o.optString("style", "AUTO")), o.optBoolean("groupApps", true),
                    o.optBoolean("directOpen", false), o.optBoolean("floating", false), overrides,
                    floatingOverrides(o.optJSONObject("appFloating")), CapsuleText.valueOf(o.optString("capsuleText", "CONTENT")),
                    AfterOpen.valueOf(o.optString("afterOpen", "KEEP")), o.optBoolean("keepAlerts", true),
                    o.optBoolean("shadeCards", false), o.optBoolean("retainOnLock", false), o.optBoolean("lockCards", false),
                    parseClones(o.optJSONObject("clones")), o.optBoolean("swipeDismiss", true), o.optBoolean("history", false),
                    o.optBoolean("suppressOriginalBanner", false));
        } catch (JSONException | IllegalArgumentException failure) { return null; }
    }
    private static Map<String, CloneRule> parseClones(JSONObject object) throws JSONException {
        Map<String, CloneRule> result = new LinkedHashMap<>();
        if (object == null) return result;
        var keys = object.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            int colon = key.indexOf(':');
            if (colon < 1 || !validPackage(key.substring(colon + 1))) throw new IllegalArgumentException();
            int serial = Integer.parseInt(key.substring(0, colon));
            if (serial < 0 || !key.startsWith(serial + ":")) throw new IllegalArgumentException();
            JSONObject item = object.getJSONObject(key);
            String floatMode = item.optString("floating");
            String appMode = item.optString("mode");
            Map<String, Mode> channels = new LinkedHashMap<>();
            JSONObject entries = item.optJSONObject("channels");
            if (entries != null) {
                var ids = entries.keys();
                while (ids.hasNext()) {
                    String id = ids.next();
                    if (id.isEmpty() || key.length() - colon + id.length() > 600) throw new IllegalArgumentException();
                    channels.put(id, Mode.valueOf(entries.getString(id)));
                }
            }
            result.put(key, new CloneRule(item.optBoolean("inherit", true), new AppRule(
                    item.optBoolean("allowed"), item.optBoolean("denied"), floatMode.isEmpty() ? null : Floating.valueOf(floatMode),
                    appMode.isEmpty() ? null : Mode.valueOf(appMode), channels)));
        }
        return result;
    }
    private static Map<String, Floating> floatingOverrides(JSONObject object) throws JSONException {
        Map<String, Floating> result = new LinkedHashMap<>();
        if (object == null) return result;
        var keys = object.keys();
        while (keys.hasNext()) {
            String app = keys.next();
            if (!validPackage(app)) throw new IllegalArgumentException();
            result.put(app, Floating.valueOf(object.getString(app)));
        }
        return result;
    }
    private static Set<String> packages(JSONArray list) throws JSONException {
        Set<String> result = new LinkedHashSet<>();
        if (list != null) for (int i = 0; i < list.length(); i++) {
            String pkg = list.getString(i); if (!validPackage(pkg)) throw new IllegalArgumentException(); result.add(pkg);
        }
        return result;
    }
    public String encode() {
        try {
            String text = new JSONObject().put("schema", 1).put("denylist", denylist)
                    .put("allowed", new JSONArray(allowed.stream().sorted().toList()))
                    .put("denied", new JSONArray(denied.stream().sorted().toList()))
                    .put("ongoing", ongoing.name()).put("ordinary", ordinary.name()).put("silent", silent.name())
                    .put("calls", calls.name()).put("original", original.name()).put("style", style.name())
                    .put("groupApps", groupApps).put("directOpen", directOpen).put("floating", floating)
                    .put("capsuleText", capsuleText.name()).put("afterOpen", afterOpen.name()).put("keepAlerts", keepAlerts)
                    .put("shadeCards", shadeCards).put("retainOnLock", retainOnLock).put("lockCards", lockCards)
                    .put("appFloating", encodeEnums(appFloating)).put("overrides", encodeEnums(overrides))
                    .put("clones", encodeClones()).put("swipeDismiss", swipeDismiss).put("history", history)
                    .put("suppressOriginalBanner", suppressOriginalBanner).toString();
            if (text.length() > 16384) throw new IllegalArgumentException("Too many notification rules");
            return text;
        } catch (JSONException failure) { throw new IllegalArgumentException(failure); }
    }
    private JSONObject encodeClones() throws JSONException {
        JSONObject values = new JSONObject();
        for (var entry : clones.entrySet()) {
            CloneRule rule = entry.getValue();
            AppRule app = rule.app();
            values.put(entry.getKey(), new JSONObject().put("inherit", rule.inherit()).put("allowed", app.allowed())
                    .put("denied", app.denied()).put("floating", app.floating() == null ? null : app.floating().name())
                    .put("mode", app.mode() == null ? null : app.mode().name()).put("channels", encodeEnums(app.channels())));
        }
        return values;
    }
    AppRule mainRule(String pkg) {
        Map<String, Mode> channels = new LinkedHashMap<>();
        String prefix = pkg + "/";
        overrides.forEach((key, value) -> {
            if (key.startsWith(prefix)) channels.put(key.substring(prefix.length()), value);
        });
        return new AppRule(allowed.contains(pkg), denied.contains(pkg), appFloating.get(pkg), overrides.get(pkg), channels);
    }
    NotificationCloudRules editClone(String pkg, int serial, CloneRule rule) {
        if (!validPackage(pkg) || serial < 0) throw new IllegalArgumentException();
        var nextClones = new LinkedHashMap<>(clones);
        nextClones.put(serial + ":" + pkg, rule);
        return new NotificationCloudRules(denylist, allowed, denied, ongoing, ordinary, silent, calls, original,
                style, groupApps, directOpen, floating, overrides, appFloating, capsuleText, afterOpen,
                keepAlerts, shadeCards, retainOnLock, lockCards, nextClones, swipeDismiss, history, suppressOriginalBanner);
    }
    private static JSONObject encodeEnums(Map<String, ? extends Enum<?>> values) throws JSONException {
        JSONObject object = new JSONObject();
        for (var entry : values.entrySet()) object.put(entry.getKey(), entry.getValue().name());
        return object;
    }
    NotificationCloudRules editApp(String pkg, boolean allow, boolean deny, Floating floating,
                                   Map<String, Mode> modes) {
        if (!validPackage(pkg)) throw new IllegalArgumentException();
        var nextAllowed = new LinkedHashSet<>(allowed);
        var nextDenied = new LinkedHashSet<>(denied);
        if (allow) nextAllowed.add(pkg); else nextAllowed.remove(pkg);
        if (deny) nextDenied.add(pkg); else nextDenied.remove(pkg);
        var nextFloating = new LinkedHashMap<>(appFloating);
        if (floating == null) nextFloating.remove(pkg); else nextFloating.put(pkg, floating);
        var nextModes = new LinkedHashMap<>(overrides);
        nextModes.keySet().removeIf(key -> key.equals(pkg) || key.startsWith(pkg + "/"));
        nextModes.putAll(modes);
        return new NotificationCloudRules(denylist, nextAllowed, nextDenied, ongoing, ordinary, silent, calls, original,
                style, groupApps, directOpen, this.floating, nextModes, nextFloating, capsuleText, afterOpen,
                keepAlerts, shadeCards, retainOnLock, lockCards, clones, swipeDismiss, history, suppressOriginalBanner);
    }
    public List<String> summary() {
        return List.of(denylist ? "黑名单 · " + denied.size() + " 个应用" : "白名单 · " + allowed.size() + " 个应用",
                "持续／进度：" + label(ongoing) + "；普通：" + label(ordinary) + "；静默：" + label(silent));
    }
    public static String label(Mode mode) { return switch (mode) { case NATIVE -> "不转换"; case CAPSULE -> "仅胶囊"; case EXPAND -> "胶囊并自动展开"; }; }
    public NotificationCloudRules with(String key, Object value) {
        try {
            var json = new JSONObject(encode()); json.put(key, value);
            NotificationCloudRules next = parse(json.toString());
            if (next == null) throw new IllegalArgumentException("Invalid notification option");
            return next;
        } catch (JSONException failure) { throw new IllegalArgumentException(failure); }
    }
}
