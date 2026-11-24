package dev.howard.colorosenhance.notifications;

import java.util.List;

/** Shared native/Compose setting rows. The serialized policy is one atomic preference. */
public final class NotificationSettingsOptions {
    public record Option(String section, String key, String title, String summary, List<String> labels, List<String> values) {
        public boolean available(NotificationCloudRules rules) { return !key.equals("lockCards") || rules.retainOnLock(); }
        public boolean toggle() { return values.isEmpty(); }
        public int selection(NotificationCloudRules rules) { return toggle() ? Boolean.TRUE.equals(value(rules, key)) ? 1 : 0 : Math.max(0, values.indexOf(value(rules, key).toString())); }
        public String description(NotificationCloudRules rules) { return toggle() ? summary : labels.get(selection(rules)); }
        public NotificationCloudRules change(NotificationCloudRules rules, int index) { return rules.with(key, toggle() ? index == 1 : values.get(index)); }
    }
    private static Option choice(String section, String key, String title, String... labels) {
        List<String> values = switch (key) {
            case "original" -> List.of("KEEP", "COLLECT", "HIDE");
            case "style" -> List.of("AUTO", "GLASS", "COMPACT", "PROGRESS");
            case "capsuleText" -> List.of("CONTENT", "APP");
            case "afterOpen" -> List.of("KEEP", "CARD", "BOTH");
            default -> List.of("NATIVE", "CAPSULE", "EXPAND");
        };
        return new Option(section, key, title, "", List.of(labels), values);
    }
    private static Option mode(String key, String title) { return choice("通知种类", key, title, "不转换，保留原厂", "仅胶囊", "胶囊并自动展开"); }
    private static Option flag(String section, String key, String title, String summary) { return new Option(section, key, title, summary, List.of(), List.of()); }
    public static final List<Option> ALL = List.of(
            flag("应用筛选", "denylist", "使用黑名单模式", "关闭时仅处理白名单；开启时不处理黑名单。两份名单分别保存。"),
            mode("ongoing", "持续／进度通知"), mode("ordinary", "普通通知"), mode("silent", "静默通知"), mode("calls", "第三方标准通话通知"),
            choice("原通知处理", "original", "转换成功后的原通知", "保留原通知", "收纳到“已转为流体云”", "隐藏显示，保留系统记录"),
            flag("原通知处理", "keepAlerts", "保留通知响铃和震动", "开启时跟随系统渠道及勿扰设置。关闭仅影响参与转换的系统通知提醒。关闭后首次加载需要系统框架作用域和重启手机。"),
            flag("原通知处理", "shadeCards", "在通知中心显示流体云卡片", "为通知中心额外构建和更新一份卡片，频繁更新时会增加 SystemUI 负载。原通知仍按上面的方式处理。"),
            flag("原通知处理", "retainOnLock", "锁屏时保留通知转流体云", "锁屏时继续转换系统允许完整显示的通知，并沿用原通知的收纳或隐藏设置。关闭后，锁屏时恢复原通知。"),
            flag("原通知处理", "lockCards", "锁屏时显示通知流体云卡片", "原通知可在锁屏完整展示时，卡片会保留在锁屏通知列表，直到被清除或原通知结束。"),
            choice("胶囊外观", "capsuleText", "胶囊右侧内容", "通知内容", "应用名称"),
            choice("卡片与交互", "style", "卡片样式", "自动适配", "玻璃卡片", "紧凑消息", "进度卡片（有进度时）"),
            flag("卡片与交互", "groupApps", "合并同类通知", "按应用或会话合并。在通知中心和锁屏卡片内左右滑动可翻页。长按选择「查看本组」可查看原通知。"),
            flag("卡片与交互", "directOpen", "点击胶囊直接打开通知", "关闭时点击展开卡片；开启时直接打开原始通知目标。"),
            flag("卡片与交互", "floating", "默认在卡片显示浮窗按钮", "设备与通知目标支持时，在卡片上显示「浮窗打开」，可按应用覆盖。支持浮窗的通知也可通过长按菜单打开。"),
            choice("卡片与交互", "afterOpen", "打开通知后清除", "不清除", "仅清除流体云卡片", "清除原通知和卡片"));
    private static Object value(NotificationCloudRules r, String key) {
        return switch (key) { case "denylist" -> r.denylist(); case "ongoing" -> r.ongoing(); case "ordinary" -> r.ordinary();
            case "silent" -> r.silent(); case "calls" -> r.calls(); case "original" -> r.original(); case "style" -> r.style();
            case "groupApps" -> r.groupApps(); case "directOpen" -> r.directOpen(); case "floating" -> r.floating();
            case "capsuleText" -> r.capsuleText(); case "afterOpen" -> r.afterOpen(); case "keepAlerts" -> r.keepAlerts();
            case "shadeCards" -> r.shadeCards();
            case "retainOnLock" -> r.retainOnLock(); case "lockCards" -> r.lockCards();
            default -> throw new IllegalArgumentException(key); };
    }
    private NotificationSettingsOptions() { }
}
