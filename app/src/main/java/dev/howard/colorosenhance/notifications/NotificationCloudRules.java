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
        boolean keepAlerts, boolean shadeCards, boolean retainOnLock, boolean lockCards) {
    public static final String KEY = "notification_cloud_rules";
    public static final String ENABLED = "notification_cloud_enabled";
    public enum Mode { NATIVE, CAPSULE, EXPAND }
    public enum Original { KEEP, COLLECT, HIDE }
    public enum Style { AUTO, GLASS, COMPACT, PROGRESS }
    public enum Kind { ONGOING, ORDINARY, SILENT, CALL }
    public enum Floating { ON, OFF }
    public enum CapsuleText { CONTENT, APP }
    public enum AfterOpen { KEEP, CARD, BOTH }
    public static final NotificationCloudRules DEFAULT = new NotificationCloudRules(false, Set.of(), Set.of(),
            Mode.CAPSULE, Mode.CAPSULE, Mode.NATIVE, Mode.NATIVE, Original.COLLECT, Style.AUTO,
            true, false, false, Map.of(), Map.of(), CapsuleText.CONTENT, AfterOpen.KEEP, true, false, false, false);

    public NotificationCloudRules {
        allowed = Set.copyOf(allowed); denied = Set.copyOf(denied); overrides = Map.copyOf(overrides);
        appFloating = Map.copyOf(appFloating);
    }
    public boolean accepts(String pkg) { return denylist ? !denied.contains(pkg) : allowed.contains(pkg); }
    public boolean floating(String pkg) {
        Floating override = appFloating.get(pkg);
        return override == null ? floating : override == Floating.ON;
    }
    public boolean replaceHeadsUp() { return original != Original.KEEP; }
    public Mode mode(String pkg, String channel, Kind kind) {
        if (!accepts(pkg)) return Mode.NATIVE;
        Mode value = overrides.get(pkg + "/" + channel);
        if (value == null) value = overrides.get(pkg);
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
                    o.optBoolean("shadeCards", false), o.optBoolean("retainOnLock", false), o.optBoolean("lockCards", false));
        } catch (JSONException | IllegalArgumentException failure) { return null; }
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
                    .put("appFloating", encodeEnums(appFloating)).put("overrides", encodeEnums(overrides)).toString();
            if (text.length() > 16384) throw new IllegalArgumentException("Too many notification rules");
            return text;
        } catch (JSONException failure) { throw new IllegalArgumentException(failure); }
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
                keepAlerts, shadeCards, retainOnLock, lockCards);
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
