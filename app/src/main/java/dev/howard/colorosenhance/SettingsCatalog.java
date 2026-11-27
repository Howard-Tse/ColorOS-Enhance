package dev.howard.colorosenhance;

import dev.howard.colorosenhance.notifications.NotificationCloudRules;
import dev.lsposed.fluidcloud.CapsuleMarqueeOptions;
import dev.lsposed.fluidcloud.CapsulePosition;
import dev.lsposed.fluidcloud.CloudCorners;
import dev.lsposed.fluidcloud.CardSpacing;
import dev.lsposed.fluidcloud.MediaWaveBands;
import dev.lsposed.fluidcloud.MediaWaveOptions;
import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.util.StringJoiner;

/** Values use integer units so tenths of a dp persist without float drift. */
public final class SettingsCatalog {
    public static final String RESET_NUMBER_LABEL = "恢复此项默认值";
    public static final String WAVE_BANDS_TITLE = "六频段设置";
    public static final String WAVE_BANDS_HELP = "拖动频率轴上的分界点，或在下方输入 20–24000 Hz。六个区间从左到右对应六根柱。没有可用频点的区间显示为短柱。";
    public static final String WAVE_BANDS_ERROR = "请输入 20–24000 Hz 内严格递增的整数。";
    public static final String WAVE_RESET_TITLE = "恢复实时律动默认配置";
    public static final String WAVE_RESET_HELP = "恢复六频段、读取间隔、灵敏度、平滑时间和暂停时的柱高选项。";

    public static String waveBandEdgeTitle(int index) {
        if (index == 0) return "起始频率（Hz）";
        if (index == MediaWaveBands.EDGE_COUNT - 1) return "结束频率（Hz）";
        return "第 " + index + "／" + (index + 1) + " 段分界（Hz）";
    }

    public static String waveBandsSummary(MediaWaveBands bands) {
        StringJoiner ranges = new StringJoiner(" · ");
        for (int index = 0; index < MediaWaveBands.EDGE_COUNT - 1; index++) {
            ranges.add(bands.edgeHz(index) + "–" + bands.edgeHz(index + 1));
        }
        return ranges + " Hz";
    }

    public static final String INSTALLER_HELP = "选择已安装且能处理 APK 的应用，接管普通应用打开 APK 的请求。请先在所选应用中完成安装方式与权限配置。\n\n"
            + "首次使用或更新此功能后，确认 LSPosed 作用域包含“系统框架”，并完整重启手机。加载后开关对后续请求生效；“一键重启作用域”不能加载此功能。\n\n"
            + "未选择应用、目标被卸载或停用、无法处理当前 APK 请求时，保留系统流程。ADB、卸载、安装会话确认和跨用户请求保持原流程。";
    public record Visibility(String key, boolean value) { }
    public record SceneSummary(String keepKey, String animationKey) { }
    public record Item(String section, String key, String title, String description, boolean defaultFlag,
                       String valueKey, int valueDefault, int min, int max, int step, int divisor, String unit,
                       Visibility visibility, SceneSummary sceneSummary) {
        public boolean adjustable() { return valueKey != null; }
        public boolean valueOnly() { return adjustable() && key.equals(valueKey); }
        public boolean valueEnabled(Map<String,?> values) {
            return valueOnly() || (values.containsKey(key)?Boolean.TRUE.equals(values.get(key)):defaultFlag);
        }
        public boolean visible(Map<String, ?> values) {
            if (key.startsWith("landscape_") && !key.equals("landscape_follow_portrait")
                    && Boolean.TRUE.equals(values.get("landscape_follow_portrait"))) return false;
            if (visibility == null) return true;
            return Boolean.TRUE.equals(values.get(visibility.key())) == visibility.value();
        }
        public String summary(Map<String, ?> values) {
            if (sceneSummary == null) return description;
            String mode = Boolean.TRUE.equals(values.get(sceneSummary.keepKey())) ? "场景期间保持显示"
                    : Boolean.TRUE.equals(values.get(sceneSummary.animationKey())) ? "使用收缩/展开动画"
                    : "跟随系统";
            return description == null || description.isBlank() ? mode : description + " · " + mode;
        }
    }
    public record Group(String key, String title, String summary, List<Item> items) { }
    public static String masterTitle(Group group) {
        return "启用" + group.title() + (List.of("aod", "cloud", "battery", "icons", "control_center").contains(group.key()) ? "增强" : "");
    }
    private static Item flag(String section,String key,String title,String description) {
        return flag(section,key,title,description,false);
    }
    private static Item flag(String section,String key,String title,String description,boolean fallback) {
        return new Item(section,key,title,description,fallback,null,0,0,0,1,1,"",null,null);
    }
    private static Item value(String section,String key,String title,String description,String valueKey,
                              int valueDefault,int min,int max,int step,int divisor,String unit) {
        return new Item(section,key,title,description,false,valueKey,valueDefault,min,max,step,divisor,unit,null,null);
    }
    private static List<Item> cloudItems() {
        List<Item> items = new ArrayList<>();
        items.add(flag("通知转流体云", NotificationCloudRules.ENABLED, "通知转流体云",
                "按应用、渠道与通知种类转换，默认空白名单。频繁更新的进度或网速通知会增加 SystemUI 负载，耗电敏感时可排除这些应用或渠道。"));
        items.add(new Item("通知转流体云", "notification_popup_seconds", "自动展开停留时间",
                "仅用于选择了自动展开的通知；不打断操作，勿扰或锁屏时不自动展开。", false,
                "notification_popup_seconds", 3, 1, 15, 1, 1, "秒", new Visibility(NotificationCloudRules.ENABLED, true), null));
        items.add(new Item("通知转流体云", "notification_capsule_max_dp", "通知胶囊最大长度",
                "0 dp 保持原厂宽度。仅限制通知胶囊，内容超出时省略。最小宽度 48 dp。", false,
                "notification_capsule_max_dp", 0, 0, 240, 1, 1, "dp", new Visibility(NotificationCloudRules.ENABLED, true), null));
        items.add(flag("媒体","keep_paused_music","保持媒体流体云",
                "有效媒体会话未播放或暂停时也保持显示；会话结束或应用退出后恢复系统处理。"));
        items.add(flag("媒体","media_card_in_shade","在通知中心显示媒体卡片",
                "媒体流体云存在时，也在通知中心显示系统媒体卡片。锁屏按系统处理。"));
        items.add(flag("媒体","realtime_media_wave","媒体实时律动",
                "播放且胶囊可见时持续采样并计算六频段，可能增加耗电。遇到音效冲突、硬件卸载或无法安全采集时使用原厂动画。更新后需重启系统界面。"));
        MediaWaveOptions defaults = MediaWaveOptions.DEFAULTS;
        items.add(new Item("媒体实时律动参数",MediaWaveOptions.MIX_COMPATIBILITY_KEY,"音效兼容采样",
                "遇到音效冲突时尝试采集系统输出混音。柱高可能受其他应用声音影响，也可能影响硬件卸载并增加耗电。",false,
                null,0,0,0,1,1,"",new Visibility("realtime_media_wave",true),null));
        items.add(waveNumber("wave_interval_ms","读取间隔","间隔越短，采样与频谱计算越频繁，可能更耗电。默认 80 ms。",
                defaults.intervalMs(), MediaWaveOptions.INTERVAL_MIN_MS, MediaWaveOptions.INTERVAL_MAX_MS, MediaWaveOptions.INTERVAL_STEP_MS, "ms"));
        items.add(waveNumber("wave_sensitivity","灵敏度","调整六个频段的整体柱高。",
                defaults.sensitivityPercent(), MediaWaveOptions.SENSITIVITY_MIN_PERCENT, MediaWaveOptions.SENSITIVITY_MAX_PERCENT, MediaWaveOptions.SENSITIVITY_STEP_PERCENT, "%"));
        items.add(waveNumber("wave_attack_ms","上升平滑时间","越小越快跟随声音增强，越大越平缓。",
                defaults.attackMs(), MediaWaveOptions.ATTACK_MIN_MS, MediaWaveOptions.ATTACK_MAX_MS, MediaWaveOptions.ATTACK_STEP_MS, "ms"));
        items.add(waveNumber("wave_release_ms","回落平滑时间","越小回落越快，越大保留起伏越久。",
                defaults.releaseMs(), MediaWaveOptions.RELEASE_MIN_MS, MediaWaveOptions.RELEASE_MAX_MS, MediaWaveOptions.RELEASE_STEP_MS, "ms"));
        items.add(new Item("媒体实时律动参数","wave_hold_paused","暂停时保留最后柱高",
                "开启后冻结暂停那一刻的六柱；关闭时平滑回落为短柱。两种模式均释放声音采集器。",false,
                null,0,0,0,1,1,"",new Visibility("realtime_media_wave",true),null));
        items.add(value("小胶囊外观","capsule_size_enabled","自定义小胶囊尺寸",
                "下方调节横向长度，100% 为原厂尺寸。不拉伸文字和图标；尺寸过小时内容可能裁剪，最大高度受状态栏空间限制。",
                "capsule_width_percent",100,50,200,1,1,"%"));
        items.add(new Item("小胶囊外观","capsule_height_percent","纵向高度",
                "按原厂高度的比例调整，100% 保持原厂。",false,"capsule_height_percent",100,50,200,1,1,"%",
                new Visibility("capsule_size_enabled",true),null));
        String marqueeSection = "小胶囊文字滚动";
        var marquee = CapsuleMarqueeOptions.DEFAULTS;
        items.add(flag(marqueeSection, CapsuleMarqueeOptions.ENABLED, "超长文字滚动",
                "滚动显示放不下的胶囊文字。支持通知转流体云和使用系统文字控件的胶囊。"));
        items.add(marqueeNumber(marqueeSection, CapsuleMarqueeOptions.SPEED, "滚动速度",
                "每秒移动的距离。", marquee.speed(), CapsuleMarqueeOptions.MIN_SPEED, CapsuleMarqueeOptions.MAX_SPEED, "dp/s"));
        items.add(marqueeNumber(marqueeSection, CapsuleMarqueeOptions.PAUSE, "首尾停留时间",
                "每次滚动在开头和末尾各停留的时间。", marquee.pause(), 0, CapsuleMarqueeOptions.MAX_PAUSE, "ms"));
        items.add(marqueeNumber(marqueeSection, CapsuleMarqueeOptions.REPEATS, "滚动次数",
                "达到次数后回到开头。0 为持续循环。", marquee.repeats(), 0, CapsuleMarqueeOptions.MAX_REPEATS, "次"));
        items.add(flag("小胶囊外观","hide_capsule_stroke","隐藏小胶囊边框",
                "隐藏深色背景下的原生边框；关闭后按当前背景恢复系统处理。"));
        items.add(flag("小胶囊外观","hide_capsule_arrows","隐藏左右切换提示",
                "隐藏小胶囊两侧的切换指示，不影响左右滑动。关闭后恢复原生提示规则。"));
        items.add(value("小胶囊外观","capsule_corners_enabled","自定义小胶囊圆角",
                "调节胶囊两端的圆角。超过高度的一半后，圆角继续向横向延伸。关闭后恢复原厂外形。","capsule_corner_dp",16,0,24,1,1,"dp"));
        items.add(flag("小胶囊外观","capsule_continuous_corners","小胶囊连续圆角",
                "启用自定义圆角后，使直边与弯曲边缘平滑衔接。"));
        items.add(flag("小胶囊外观","capsule_keep_in_foreground","前台应用内保留流体云胶囊",
                "开启后，来源应用在前台时仍可显示自己的胶囊。关闭时保持 ColorOS 原厂行为。"));
        addCapsulePosition(items, "竖屏", "capsule_");
        addCapsulePosition(items, "横屏", "capsule_landscape_");
        items.add(flag("小胶囊切换","skip_empty_capsule","跳过空白状态，循环切换",
                "左右滑动仅在当前有效流体云之间循环；只有一个时保持当前项。不影响系统主动隐藏流体云。"));
        items.add(flag("点击交互","block_capsule_click_top","防止点击流体云触发回顶",
                "点击流体云时只执行原生流体云操作，不同时让当前应用回到顶部；状态栏其他区域保持系统处理。"));
        items.add(flag("展开卡片","selected_card_first","当前胶囊卡片优先置顶",
                "展开时将当前小胶囊对应卡片放在最上面；左右滑动切换胶囊后同步重排，其余卡片保留相对顺序。关闭后恢复原厂排序。"));
        items.add(flag("展开卡片","camera_card_expansion","从摄像头区域展开",
                "卡片顶部贴合摄像头区域，展开与收起使用同一位置，并为内容保留避让空间。横屏挖孔位于侧边时保持系统处理。更新模块后需重启系统界面。"));
        items.add(value("展开卡片","card_dim_enabled","展开时压暗背景",
                "随卡片展开加深、收起减弱；在通知中心或控制中心中也生效。关闭恢复原厂。",
                "card_dim_percent",30,0,100,1,1,"%"));
        items.add(value("展开卡片","card_corners_enabled","自定义展开卡片圆角",
                "调节展开卡片的半径。较大的半径可能增加较矮卡片的高度，重新展开卡片后生效。",
                "card_corner_dp",28,0,CloudCorners.CARD_RADIUS_MAX_DP,1,1,"dp"));
        items.add(flag("展开卡片","card_continuous_corners","展开卡片连续圆角",
                "启用自定义圆角后，使直边与弯曲边缘平滑衔接。重新展开卡片后生效。"));
        addCardSpacing(items, CardSpacing.SIDE, "卡片左右外边距", "调整卡片两侧到屏幕边缘的距离。");
        addCardSpacing(items, CardSpacing.GAP, "卡片间距", "从第二张卡片起调整与上一张的距离。");
        addCardSpacing(items, CardSpacing.HORIZONTAL, "内容水平内边距", "调整卡片正文左右的留白，可缩小的幅度取决于模板。");
        addCardSpacing(items, CardSpacing.VERTICAL, "内容垂直内边距", "调整正文上下的留白，首张卡片仍会避让摄像头。");
        items.add(flag("场景控制","unbind_status_bar","流体云与状态栏解绑",
                "开启后重启系统界面。可在下方为竖屏或横屏选择「保持显示」或「动画补全」。未配置的场景仍跟随系统。动态胶囊可能增加耗电。"));
        items.add(flag("场景控制","capsule_lock_exit","锁屏时收缩胶囊",
                "锁屏时让可见的流体云胶囊收缩并渐隐。可独立于「流体云与状态栏解绑」开启。"));
        items.add(flag("场景控制","capsule_on_top","流体云胶囊置顶",
                "提高胶囊窗口层级，胶囊可显示在展开卡片上方。修改后重启系统界面。"));
        items.add(flag("场景控制","capsule_keep_during_launch","应用转场时保持胶囊显示",
                "跳过同方向应用转场中的胶囊淡出，例如从控制中心打开设置。首次使用需重启手机。之后修改在下次转场生效，显隐仍遵循场景设置。",true));
        addOrientationScenes(items, "竖屏", "");
        items.add(flag("横屏配置", "landscape_follow_portrait", "横屏与竖屏一致",
                "开启后横屏沿用竖屏的全部场景设置；关闭后单独配置横屏，已保存的横屏选项会保留。"));
        addOrientationScenes(items, "横屏", "landscape_");
        return List.copyOf(items);
    }
    private static void addCardSpacing(List<Item> items, String key, String title, String description) {
        items.add(value("展开卡片", key, title, description + " 正值增加，负值减少，0 dp 恢复原厂。",
                key, 0, CardSpacing.MIN, CardSpacing.MAX, 1, 1, "dp"));
    }
    private static Item waveNumber(String key,String title,String description,int fallback,int min,int max,int step,String unit){
        return new Item("媒体实时律动参数",key,title,description,false,key,fallback,min,max,step,1,unit,
                new Visibility("realtime_media_wave",true),null);
    }
    private static Item marqueeNumber(String section, String key, String title, String description, int fallback,
                                      int min, int max, String unit) {
        return new Item(section, key, title, description, false, key, fallback, min, max, 1, 1, unit,
                new Visibility(CapsuleMarqueeOptions.ENABLED, true), null);
    }
    private static void addCapsulePosition(List<Item> items, String orientation, String prefix) {
        String horizontal = prefix + "offset_x_dp", vertical = prefix + "offset_y_dp";
        items.add(value("小胶囊位置", horizontal, orientation + "横向位置",
                "负值向左，正值向右，0 dp 为原厂位置。偏移受状态栏可用空间限制。",
                horizontal, 0, -CapsulePosition.HORIZONTAL_LIMIT_DP, CapsulePosition.HORIZONTAL_LIMIT_DP, 1, 1, "dp"));
        items.add(value("小胶囊位置", vertical, orientation + "纵向位置",
                "capsule_".equals(prefix)
                        ? "负值向上，正值向下，0 dp 为原厂位置。竖屏解锁后可下移至状态栏下方。"
                        : "负值向上，正值向下，0 dp 为原厂位置。可移动距离随胶囊高度变化。",
                vertical, 0, -CapsulePosition.VERTICAL_LIMIT_DP, CapsulePosition.VERTICAL_LIMIT_DP, 1, 1, "dp"));
    }
    private static void addOrientationScenes(List<Item> items, String orientation, String prefix) {
        addScene(items,orientation+" · 进入/退出全屏",prefix+"fullscreen",prefix+"fullscreen_animation",
                "应用隐藏状态栏时按所选模式处理流体云。",false);
        addScene(items,orientation+" · 进入/退出最近任务",prefix+"recents",prefix+"recents_shrink",
                "使用已适配的最近任务状态栏请求。",false);
        items.add(flag(orientation+" · 下拉状态栏",prefix+"shade_keep","保持显示",
                "下拉通知栏或控制中心时保持普通流体云；锁屏、隐私状态和迷你胶囊仍由系统处理。"));
        items.add(new Item(orientation+" · 下拉状态栏",prefix+"shade_collapse_on_open","展开流体云前收起面板",
                "开启时先收起通知中心或控制中心，再展开流体云；关闭时直接在面板上展开。",false,
                null,0,0,0,1,1,"",new Visibility(prefix+"shade_keep",true),null));
        addScene(items,orientation+" · 其他隐藏场景",prefix+"other_keep",prefix+"other_animation",
                "实验性：仅处理通过安全检查且能够配对恢复的未知隐藏请求。",true);
    }
    public static boolean controlEnabled(String group,String key,Map<String,?> values) {
        if ("icons".equals(group)) return Boolean.TRUE.equals(values.get("enabled"));
        if (!"cloud".equals(group)) return true;
        if (!Boolean.TRUE.equals(values.get("enabled"))) return false;
        if (NotificationCloudRules.ENABLED.equals(key)) return true;
        if ("notification_popup_seconds".equals(key) || "notification_capsule_max_dp".equals(key))
            return Boolean.TRUE.equals(values.get(NotificationCloudRules.ENABLED));
        if(key.startsWith("wave_"))return Boolean.TRUE.equals(values.get("realtime_media_wave"));
        if (CapsuleMarqueeOptions.ENABLED.equals(key)) return true;
        if (key.startsWith("capsule_marquee_")) return Boolean.TRUE.equals(values.get(CapsuleMarqueeOptions.ENABLED));
        if ("capsule_height_percent".equals(key)) return Boolean.TRUE.equals(values.get("capsule_size_enabled"));
        if ("capsule_continuous_corners".equals(key)) return Boolean.TRUE.equals(values.get("capsule_corners_enabled"));
        if ("card_continuous_corners".equals(key)) return Boolean.TRUE.equals(values.get("card_corners_enabled"));
        if (key.startsWith("landscape_") && !key.equals("landscape_follow_portrait")
                && Boolean.TRUE.equals(values.get("landscape_follow_portrait"))) return false;
        if ("shade_collapse_on_open".equals(key) && !Boolean.TRUE.equals(values.get("shade_keep"))) return false;
        if ("landscape_shade_collapse_on_open".equals(key) && !Boolean.TRUE.equals(values.get("landscape_shade_keep"))) return false;
        return "keep_paused_music".equals(key) || "media_card_in_shade".equals(key) || "realtime_media_wave".equals(key) || "block_capsule_click_top".equals(key)
                || "camera_card_expansion".equals(key) || "unbind_status_bar".equals(key) || "capsule_lock_exit".equals(key)
                || List.of("hide_capsule_stroke","hide_capsule_arrows","capsule_keep_in_foreground","skip_empty_capsule","card_dim_enabled",
                        "capsule_corners_enabled","card_corners_enabled","capsule_size_enabled","capsule_offset_x_dp","capsule_offset_y_dp",
                        "capsule_landscape_offset_x_dp","capsule_landscape_offset_y_dp",
                        "selected_card_first", CardSpacing.SIDE, CardSpacing.GAP, CardSpacing.HORIZONTAL, CardSpacing.VERTICAL).contains(key)
                || Boolean.TRUE.equals(values.get("unbind_status_bar"));
    }
    public static boolean sectionVisible(Group group, String section, Map<String, ?> values) {
        return group.items().stream().anyMatch(item -> item.section().equals(section) && item.visible(values));
    }
    private static void addScene(List<Item> items, String section, String keepKey, String animationKey,
                                 String description, boolean experimental) {
        SceneSummary summary = new SceneSummary(keepKey,animationKey);
        items.add(new Item(section,keepKey,"保持显示",description,false,
                null,0,0,0,1,1,"",null,summary));
        items.add(new Item(section,animationKey,"动画补全",
                description,false,null,0,0,0,1,1,"",
                new Visibility(keepKey,false),summary));
    }
    public static final List<Group> GROUPS = List.of(
        new Group("battery", "状态栏电池", "调整内显电量的字重、字号和位置", List.of(
            value("内显文字","weight_enabled","修改字重","关闭后恢复系统字体字重。","weight",570,100,1000,10,1,""),
            value("内显文字","text_size_enabled","修改字号","仅修改电池内部百分比。","text_size_tenths_dp",100,60,140,1,10,"dp"),
            value("文字位置","horizontal_offset_enabled","水平偏移","基于系统原位置叠加，正值向右。","horizontal_offset_tenths_dp",0,-30,30,1,10,"dp"),
            value("文字位置","vertical_offset_enabled","垂直偏移","基于系统原位置叠加，正值向下。","vertical_offset_tenths_dp",0,-30,30,1,10,"dp")
        )),
        new Group("icons", "状态栏图标", "蓝牙显示规则与各图标仅下拉后显示", List.of()),
        new Group("control_center", "控制中心", "磁贴纵向展开、整页滚动与横屏分页过渡", List.of(
            flag("磁贴布局","vertical_portrait","竖屏磁贴纵向展开","把下方分页磁贴接成连续行，上下滑动整个控制中心；滚动到底后再次上滑，或从屏幕底部上滑可收起。进入编辑时暂时恢复原厂布局。"),
            flag("磁贴布局","vertical_landscape","横屏磁贴纵向展开","保留原生分栏，将右侧磁贴向下展开并纵向滚动。底部上滑可收起，编辑模式保持原厂处理。"),
            flag("滑动触感","edge_haptics","滚动到边界时震动","仅磁贴纵向展开时生效。拖动或惯性滑动到顶部、底部时使用系统列表触感；回弹不重复震动，跟随系统触感设置。"),
            new Item("分页过渡","fade_landscape","横屏分页边缘渐隐","在原厂左右翻页时，让分页外侧的越界磁贴渐变淡出；静止时不遮挡正常磁贴，外侧范围按相邻控件间距限制。",false,
                    null,0,0,0,1,1,"",new Visibility("vertical_landscape",false),null)
        )),
        new Group("aod", "全景息屏", "防误触、亮度、背景与隐藏元素", List.of(
            flag("触摸","block_tap","单击防误触，双击唤醒","仅全景息屏生效，保留系统指纹解锁。"),
            value("亮度与背景","scale_enabled","自动亮度比例","保留系统亮度上限；下次亮度更新或重新息屏生效。","scale",100,75,500,1,1,"%"),
            value("亮度与背景","floor_enabled","低亮度下限","与亮度比例独立；0 表示不额外抬高下限。","floor",0,0,20,1,1,"nit"),
            value("亮度与背景","dim_enabled","额外背景压暗","仅压暗背景，保留时钟前景。ColorOS 17 需勾选“多彩引擎”作用域并完整重启；未匹配独立背景节点时保持系统处理。","dim",0,0,100,1,1,"%"),
            flag("隐藏元素","hide_battery","隐藏电池","只影响全景息屏中的锁屏电池。"),
            flag("隐藏元素","hide_carrier","隐藏运营商名称","保留原生布局和触摸区域。"),
            flag("隐藏元素","hide_widgets","隐藏时钟旁小组件","整体隐藏右侧四个小组件，大时钟保持原样。"),
            flag("隐藏元素","hide_date","隐藏日期","隐藏大时钟下方的公历日期。"),
            flag("隐藏元素","hide_week","隐藏星期","仅隐藏星期文字。"),
            flag("隐藏元素","hide_lunar","隐藏农历／附加信息","此行改为天气或步数后仍由本项控制。"),
            flag("过渡效果","animate","隐藏元素淡入淡出","约 280 毫秒；应用内直接息屏立即隐藏，指纹解锁保持隐藏。",true)
        )),
        new Group("cloud", "流体云", "按场景选择保持显示、动画补全或跟随系统", cloudItems()),
        new Group("sim", "SIM PIN 自动解锁", "双卡独立配置，开机时自动输入 PIN", List.of(
            flag("开机显示","suppress_pin_flash","暂缓 PIN 页面闪现","仅在已发起自动验证后最多等待 5 秒，失败或超时恢复手动输入。",true)
        )),
        new Group("installer", "安装器替换", "使用选定应用打开 APK 安装请求", List.of(
            flag("跳转行为","skip_confirmation","跳过打开确认","仅跳过 APK 转交所选安装器时的“想要打开”提示，仍需在安装器中确认安装。")
        ))
    );
    public static Group find(String key) {
        return GROUPS.stream().filter(group -> group.key().equals(key)).findFirst().orElse(null);
    }
    public static Group find(String key, android.content.Context context) {
        Group group = find(key);
        return group != null && "icons".equals(key) ? new Group(group.key(), group.title(), group.summary(),
                dev.howard.colorosenhance.icons.StatusIconCatalog.items(context)) : group;
    }
}
