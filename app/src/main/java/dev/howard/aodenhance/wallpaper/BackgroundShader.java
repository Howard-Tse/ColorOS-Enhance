package dev.howard.aodenhance.wallpaper;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Changes only the final background RGB output. Intermediate textures and alpha stay native. */
final class BackgroundShader {
    static final String UNIFORM = "ceBackgroundDim";
    private static final String ENTRY = "ceNativeBackgroundMain";
    private static final Pattern MAIN = Pattern.compile("\\bvoid\\s+main\\s*\\(\\s*(?:void\\s*)?\\)");
    private static final Pattern OUTPUT = Pattern.compile("\\bout\\s+(?:(?:highp|mediump|lowp)\\s+)?vec4\\s+fragColor\\s*;");
    private static final Pattern COMMENTS = Pattern.compile("/\\*[\\s\\S]*?\\*/|//[^\\r\\n]*");
    private static final Pattern VERSION = Pattern.compile("^\\s*#version\\s+300\\s+es\\b");
    private BackgroundShader() { }

    static String patch(String original) {
        if (original == null || original.contains(UNIFORM) || original.contains(ENTRY)) return null;
        String source = withoutComments(original);
        if (!VERSION.matcher(source).find() || !OUTPUT.matcher(source).find()) return null;
        Matcher main = MAIN.matcher(source);
        if (!main.find()) return null;
        int start = main.start(), end = main.end();
        if (!source.substring(end).stripLeading().startsWith("{")) return null;
        if (main.find()) return null;
        return original.substring(0,start) + "void " + ENTRY + "()" + original.substring(end)
                + "\n// ColorOS Enhance: background output only\n"
                + "uniform float " + UNIFORM + ";\nvoid main() {\n  " + ENTRY + "();\n"
                + "  fragColor.rgb *= (1.0 - clamp(" + UNIFORM + ", 0.0, 1.0));\n}\n";
    }

    private static String withoutComments(String source) {
        char[] visible = source.toCharArray();
        Matcher comments = COMMENTS.matcher(source);
        while (comments.find())for (int i = comments.start(); i < comments.end(); i++)
            if (visible[i] != '\n' && visible[i] != '\r')visible[i] = ' ';
        return new String(visible);
    }

    static float amount(boolean enabled, boolean dimEnabled, int percent, boolean panoramic) {
        return enabled && dimEnabled && panoramic ? Math.max(0,Math.min(100,percent)) / 100f : 0f;
    }
}
