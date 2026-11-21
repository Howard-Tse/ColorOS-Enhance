package dev.howard.colorosenhance.diagnostics;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import dev.howard.colorosenhance.BuildConfig;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

final class DiagnosticEnvironment {
    private static final List<String> PACKAGES = List.of("android", "com.android.systemui", "com.oplus.systemui.plugins",
            SeedlingSdkDiagnostics.PACKAGE, "com.android.settings", "com.heytap.colorfulengine", "com.android.phone");
    private static final String COLOR_OS = "ro.build.version.oplusrom";
    private static final String COLOR_OS_LEGACY = "ro.build.version.opporom";
    private static final String OTA = "ro.build.version.ota";

    record Versions(String colorOs, String ota) { }

    private static final class Firmware {
        static final Versions VERSIONS = versions(DiagnosticEnvironment::property);
    }

    private DiagnosticEnvironment() { }

    static String summary() {
        Versions versions = Firmware.VERSIONS;
        return "模块 " + BuildConfig.VERSION_NAME + " · Android " + Build.VERSION.RELEASE + "（API " + Build.VERSION.SDK_INT + "）"
                + "\nColorOS " + present(versions.colorOs()) + " · " + present(versions.ota(), Build.DISPLAY, Build.VERSION.INCREMENTAL);
    }

    static String describe(Context context, SeedlingSdkDiagnostics.Snapshot sdk) {
        Versions versions = Firmware.VERSIONS;
        StringBuilder report = new StringBuilder();
        line(report, "模块版本", BuildConfig.VERSION_NAME + " (" + BuildConfig.VERSION_CODE + ")");
        line(report, "ColorOS 版本", versions.colorOs());
        line(report, "OTA 固件版本", versions.ota());
        line(report, "系统版本显示", Build.DISPLAY);
        line(report, "Android 版本 / API", Build.VERSION.RELEASE + " / " + Build.VERSION.SDK_INT);
        line(report, "内部版本 / Build ID", Build.VERSION.INCREMENTAL + " / " + Build.ID);
        line(report, "安全补丁", Build.VERSION.SECURITY_PATCH);
        line(report, "机型 / 设备代号", Build.MANUFACTURER + " " + Build.MODEL + " / " + Build.DEVICE);
        line(report, "ABI", String.join(", ", Build.SUPPORTED_ABIS));
        report.append("\n系统组件版本\n");
        for (String name : PACKAGES) {
            if (name.equals(SeedlingSdkDiagnostics.PACKAGE) && sdk.available()) {
                line(report, name, version(sdk.version(), sdk.code())
                        + (sdk.loaded() ? " · SystemUI 当前加载" : " · SystemUI 原厂归档（未加载）"));
                continue;
            }
            PackageInfo info = packageInfo(context, name);
            line(report, name, info == null ? "未注册为可查询的软件包" : version(info.versionName, info.getLongVersionCode()));
        }
        line(report, "Seedling SDK 采集", sdk.status().label);
        return report.toString();
    }

    static String version(String name, long code) {
        return present(name) + " (" + (code < 0 ? "未知" : code) + ")";
    }

    static Versions versions(Function<String, String> property) {
        String colorOs = clean(property.apply(COLOR_OS));
        if (colorOs.isEmpty()) colorOs = clean(property.apply(COLOR_OS_LEGACY));
        return new Versions(colorOs, clean(property.apply(OTA)));
    }

    static PackageInfo packageInfo(Context context, String name) {
        try { return context.getPackageManager().getPackageInfo(name, PackageManager.PackageInfoFlags.of(0)); }
        catch (PackageManager.NameNotFoundException | SecurityException missing) { return null; }
    }

    private static String property(String key) {
        Process process = null;
        try {
            process = new ProcessBuilder("/system/bin/getprop", key).redirectErrorStream(true).start();
            if (!process.waitFor(500, TimeUnit.MILLISECONDS) || process.exitValue() != 0) return "";
            try (var input = process.getInputStream()) {
                return new String(input.readNBytes(192), StandardCharsets.UTF_8);
            }
        } catch (IOException | SecurityException unavailable) {
            return "";
        } catch (InterruptedException cancelled) {
            Thread.currentThread().interrupt();
            return "";
        } finally {
            if (process != null) process.destroy();
        }
    }

    private static void line(StringBuilder report, String label, String value) {
        report.append(label).append("  ").append(present(value)).append('\n');
    }

    private static String present(String... choices) {
        for (String value : choices) {
            String cleaned = clean(value);
            if (!cleaned.isEmpty()) return cleaned;
        }
        return "未知";
    }

    private static String clean(String value) {
        if (value == null) return "";
        String clean = value.lines().findFirst().orElse("").replaceAll("\\p{Cntrl}", "").strip();
        return clean.length() <= 160 ? clean : clean.substring(0, 160);
    }
}
