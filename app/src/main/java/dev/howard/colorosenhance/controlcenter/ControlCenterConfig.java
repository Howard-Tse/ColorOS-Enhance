package dev.howard.colorosenhance.controlcenter;

import java.util.Map;

public record ControlCenterConfig(boolean enabled,boolean portrait,boolean landscape,boolean fadeLandscape) {
    public static final String GROUP="control_center";
    public static ControlCenterConfig from(Map<String,?> values) {
        return new ControlCenterConfig(on(values,"enabled"),on(values,"vertical_portrait"),
                on(values,"vertical_landscape"),on(values,"fade_landscape"));
    }
    private static boolean on(Map<String,?> values,String key){return Boolean.TRUE.equals(values.get(key));}
    public boolean vertical(boolean wide){return enabled && (wide?landscape:portrait);}
    public boolean fade(boolean wide){return enabled && wide && fadeLandscape && !vertical(true);}
}
