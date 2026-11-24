package dev.howard.colorosenhance.diagnostics;

/** Only these identifiers may cross a scope boundary or be written to a diagnostic file. */
public enum DiagnosticPoint {
    BATTERY(Scope.UI, "battery", "enabled", "电池图标字重、字号与位置"),
    ICONS(Scope.UI, "icons", "enabled", "图标布局、排序与下拉显示"),
    CONTROL_CENTER(Scope.UI, "control_center", "enabled", "磁贴布局、滑动与回弹"),
    AOD(Scope.UI, "aod", "enabled", "息屏显示与手势"),
    WALLPAPER(Scope.ENGINE, "aod", "dim_enabled", "多彩引擎背景压暗"),
    SCENES(Scope.UI, "cloud", "unbind_status_bar", "状态栏插件与场景交接"),
    SCENE_HOST(Scope.UI, "cloud", "unbind_status_bar", "胶囊原生宿主"),
    LOCKSCREEN(Scope.UI, "cloud", "capsule_lock_exit", "锁屏时胶囊收缩"),
    MEDIA_SHADE(Scope.UI, "cloud", "media_card_in_shade", "媒体卡片分发到通知中心"),
    MEDIA_SHADE_ROUTE(Scope.UI, "cloud", "media_card_in_shade", "媒体通知中心投递"),
    MEDIA_SHADE_ROW(Scope.UI, "cloud", "media_card_in_shade", "通知中心原生媒体行"),
    MEDIA_SHADE_STYLE(Scope.UI, "cloud", "media_card_in_shade", "通知中心普通媒体卡片样式"),
    MEDIA_SHADE_TEXT(Scope.UI, "cloud", "media_card_in_shade", "通知中心媒体文字与歌词刷新"),
    MEDIA_HOLD(Scope.UI, "cloud", "keep_paused_music", "媒体会话与暂停超时"),
    MEDIA_WAVE(Scope.UI, "cloud", "realtime_media_wave", "实时律动采集与绘制接口"),
    MEDIA_WAVE_CAPTURE(Scope.UI, "cloud", "realtime_media_wave", "实时律动采集状态"),
    CLICK_TOP(Scope.UI, "cloud", "block_capsule_click_top", "胶囊点击与状态栏回顶"),
    CAMERA_CARD(Scope.UI, "cloud", "camera_card_expansion", "摄像头区域展开"),
    CLOUD_EXTRAS(Scope.UI, "cloud", "enabled", "胶囊外观、卡片排序与背景"),
    NOTIFICATION_CLOUD(Scope.UI, "cloud", "notification_cloud_enabled", "通知收纳与卡片渲染"),
    NOTIFICATION_RENDERER(Scope.UI, "cloud", "notification_cloud_enabled", "通知流体云原生插件"),
    NOTIFICATION_ALERTS(Scope.SYSTEM, "cloud", "notification_cloud_enabled", "原通知提示音与震动策略"),
    TOKEN_FADE(Scope.SYSTEM, "cloud", "capsule_keep_during_launch", "应用转场胶囊透明度"),
    SIM(Scope.PHONE, "sim", "enabled", "SIM PIN 运行接口"),
    SETTINGS(Scope.SETTINGS, "integration", "show_entry", "系统设置入口"),
    INSTALLER(Scope.SYSTEM, "installer", "enabled", "安装器选择"),
    INSTALLER_CONFIRM(Scope.SYSTEM, "installer", "skip_confirmation", "安装器打开确认");

    public enum Scope {
        UI("系统界面", "com.android.systemui"), SYSTEM("系统框架", "android"),
        ENGINE("多彩引擎", "com.heytap.colorfulengine"), PHONE("电话", "com.android.phone"),
        SETTINGS("设置", "com.android.settings");
        public final String label, packageName;
        Scope(String label, String packageName) { this.label = label; this.packageName = packageName; }
    }

    public enum State {
        INSTALLED("Hook 已安装"), READY("接口已匹配"), ACTIVE("已观测到运行"),
        NATIVE("当前交由系统"), WAITING("等待触发"), RESTART("等待作用域重载"), PARTIAL("部分接口已回退"),
        UNSUPPORTED("接口不可用，已回退"), FAILED("运行异常，已回退");
        public final String label;
        State(String label) { this.label = label; }
    }

    public final Scope scope;
    public final String group, key, label;
    DiagnosticPoint(Scope scope, String group, String key, String label) {
        this.scope = scope; this.group = group; this.key = key; this.label = label;
    }
}
