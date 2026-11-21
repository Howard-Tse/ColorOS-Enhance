package dev.howard.colorosenhance.diagnostics;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import dev.lsposed.fluidcloud.FirmwareDexReport;
import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

final class DiagnosticInterfaces {
    private static final int CLASS_LIMIT = 128;
    private static final int MAX_DEX_BYTES = 24 * 1024 * 1024;
    private static final Pattern DEX_ENTRY = Pattern.compile("classes[0-9]*\\.dex");
    private static final String PLUGIN = "com.oplus.systemui.plugins.";
    private static final String SEEDLING = PLUGIN + "seedling.";
    private static final Target SEEDLING_SDK = new Target(SeedlingSdkDiagnostics.PACKAGE, List.of(
            "com.oplus.seedling.sdk.plugin.PluginManager", "com.oplus.seedling.framework.manager.SeedlingManager",
            "com.oplus.seedling.framework.manager.SeedlingSdkInternal", "com.oplus.seedling.sdk.manager.ISeedlingManager"),
            List.of());

    record Target(String name, List<String> anchors, List<String> families) {
        boolean matches(String name) {
            return anchors.contains(name) || families.stream().anyMatch(name::startsWith);
        }
        int relatedRemaining(Set<String> found) {
            return Math.max(0, CLASS_LIMIT - anchors.size()
                    - (int) found.stream().filter(name -> !anchors.contains(name)).count());
        }
    }

    static final List<Target> TARGETS = List.of(
        new Target("com.android.systemui", List.of(
                "com.oplus.systemui.statusbar.seeding.CapsulePluginContainerController",
                "com.oplus.systemui.statusbar.seeding.SeedlingPluginManager",
                "com.oplus.systemui.statusbar.SysUiStateControllerImpl",
                "com.android.systemui.plugins.statusbar.SeedlingPlugin",
                "com.android.systemui.keyguard.OplusBlackScreenGestureCallback",
                "com.android.systemui.statusbar.window.data.repository.StatusBarWindowStatePerDisplayRepositoryImpl",
                "com.oplus.systemui.keyguard.ui.view.section.OplusKgdMaskSection",
                "com.oplus.keyguard.ui.OplusKeyguardStyleClock",
                "com.oplus.keyguard.plugin.KeyguardPlugin",
                "com.oplus.systemui.statusbar.notification.customcard.OplusCustomRowControllerManager",
                "com.oplus.systemui.statusbar.notification.customcard.OplusCustomRow",
                "com.android.systemui.battery.BatteryMeterView"),
                List.of("com.oplus.systemui.aod.scene.PanoramicAod", "com.oplus.systemui.statusbar.seeding.",
                        "com.oplus.systemui.statusbar.phone.OplusStatusBar", "com.oplus.systemui.battery.OplusBattery",
                        "com.android.systemui.battery.BatteryMeterView")),
        new Target("com.oplus.systemui.plugins", List.of(
                SEEDLING + "capsule.ui.view.CapsuleContainerRoot", SEEDLING + "capsule.ui.view.CapsuleContainer",
                SEEDLING + "capsule.ui.view.CapsuleView", SEEDLING + "capsule.data.repo.d",
                SEEDLING + "card.ui.view.CardContainer", SEEDLING + "card.ui.view.CardView",
                SEEDLING + "card.ui.view.InstantContainer", SEEDLING + "card.window.i",
                SEEDLING + "plugin.process.StateChangeHandler", SEEDLING + "data.interactor.l",
                PLUGIN + "shared.data.parse.s", PLUGIN + "qs.OplusQSRootView",
                PLUGIN + "qs.OplusQSRootViewComponent", PLUGIN + "qs.customize.view.animation.pull.PullAnimationProxy",
                PLUGIN + "shared.template.component.view.CustomLottieView",
                "com.oplus.systemui.livealert.notification.origin.d",
                "m4.q", "h4.e", "a5.h", "a5.i", "k3.b", "w4.j", "x4.h", "u4.d", "t5.i"),
                List.of(SEEDLING + "card.window.", SEEDLING + "plugin.process.",
                        SEEDLING + "capsule.data.repo.", SEEDLING + "notification.",
                        PLUGIN + "shared.template.component.media.view.LyricsRecyclerView")),
        SEEDLING_SDK,
        new Target("com.android.settings", List.of("com.oplus.settings.widget.preference.SettingsPreferenceCategory",
                "com.oplus.settings.widget.preference.SettingsSimpleJumpPreference"),
                List.of("com.oplus.settings.system.OplusSystem", "com.oplus.settings.widget.preference.SettingsTitle")),
        new Target("com.heytap.colorfulengine", List.of("com.oplus.vfxsdk.naive2.parse.COERenderer",
                "com.oplus.vfxsdk.naive2.coe.engine.NativeEngine", "com.oplus.vfxsdk.naive2.COEEngine"), List.of()),
        new Target("com.android.phone", List.of("com.android.phone.PhoneInterfaceManager"),
                List.of("com.android.phone.simlock.", "com.android.phone.SimPin"))
    );

    private DiagnosticInterfaces() { }

    static int write(Context context, ZipOutputStream zip, SeedlingSdkDiagnostics.Snapshot sdk) throws IOException {
        int files = 0;
        for (Target target : TARGETS) {
            zip.putNextEntry(new ZipEntry("interfaces/" + target.name() + ".txt"));
            var writer = new BufferedWriter(new OutputStreamWriter(zip, StandardCharsets.UTF_8));
            int matches = target == SEEDLING_SDK ? writeSeedling(context, sdk, writer) : writePackage(context, target, writer);
            if (matches != 0) files++;
            writer.flush();
            zip.closeEntry();
        }
        return files;
    }

    private static int writeSeedling(Context context, SeedlingSdkDiagnostics.Snapshot snapshot, Writer output) throws IOException {
        if (!snapshot.available()) {
            output.write("Loaded SDK unavailable: " + snapshot.status().label + "\n");
            output.write("Fallback: registered package, if available\n");
            return writePackage(context, SEEDLING_SDK, output);
        }
        output.write(SeedlingSdkDiagnostics.PACKAGE + "\n");
        output.write(snapshot.loaded() ? "Source: active SystemUI SDK class loader\n"
                : "Source: OEM-configured SystemUI SDK archive (not loaded)\n");
        output.write("Version: " + DiagnosticEnvironment.version(snapshot.version(), snapshot.code()) + "\n");
        output.write(snapshot.interfaces());
        return snapshot.classes();
    }

    static int writeLoadedSeedling(File archive, Writer output) throws IOException {
        Set<String> found = new LinkedHashSet<>();
        writeApk(archive, "seedling-sdk", SEEDLING_SDK, found, output);
        return finishReport(SEEDLING_SDK, found, output);
    }

    private static int writePackage(Context context, Target target, Writer output) throws IOException {
        var info = DiagnosticEnvironment.packageInfo(context, target.name());
        var app = info == null ? null : info.applicationInfo;
        output.write(target.name() + "\n");
        if (app == null || (app.flags & (ApplicationInfo.FLAG_SYSTEM | ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) == 0) {
            output.write("package unavailable or not installed as a system package\n");
            return 0;
        }
        List<String> paths = new ArrayList<>();
        paths.add(app.sourceDir);
        if (app.splitSourceDirs != null) paths.addAll(Arrays.asList(app.splitSourceDirs));
        Set<String> found = new LinkedHashSet<>();
        for (int i = 0; i < paths.size() && found.size() < CLASS_LIMIT; i++) {
            String entry = i == 0 ? "base" : "split-" + i;
            String path = paths.get(i);
            if (path == null) output.write("[" + entry + "] APK unavailable\n");
            else writeApk(new File(path), entry, target, found, output);
        }
        return finishReport(target, found, output);
    }

    private static int finishReport(Target target, Set<String> found, Writer output) throws IOException {
        output.write("\nMatched classes: " + found.size()
                + (target.relatedRemaining(found) == 0 ? " (related-class limit reached)" : "") + "\n");
        List<String> absent = target.anchors().stream().filter(name -> !found.contains(name)).toList();
        if (!absent.isEmpty()) output.write("Candidate anchors not seen (including other firmware variants):\n  "
                + String.join("\n  ", absent) + "\n");
        return found.size();
    }

    static void writeApk(File path, String label, Target target, Set<String> found, Writer output) throws IOException {
        ZipFile archive;
        try { archive = new ZipFile(path); }
        catch (IOException | SecurityException unavailable) {
            output.write("[" + label + "] APK unavailable\n");
            return;
        }
        try (archive) {
            var entries = archive.entries();
            while (entries.hasMoreElements() && found.size() < CLASS_LIMIT) {
                var entry = entries.nextElement();
                if (!DEX_ENTRY.matcher(entry.getName()).matches()) continue;
                String source = label + "/" + entry.getName();
                if (entry.getSize() < 0 || entry.getSize() > MAX_DEX_BYTES) {
                    output.write("[" + source + "] DEX size outside collection limit\n");
                    continue;
                }
                byte[] bytes;
                try (var stream = archive.getInputStream(entry)) {
                    bytes = stream.readNBytes((int) entry.getSize() + 1);
                } catch (IOException unavailable) {
                    output.write("[" + source + "] Could not read DEX\n");
                    continue;
                }
                if (bytes.length != entry.getSize()) {
                    output.write("[" + source + "] Unexpected DEX size\n");
                    continue;
                }
                try {
                    found.addAll(FirmwareDexReport.write(bytes, source, name -> !found.contains(name) && target.matches(name),
                            target.anchors()::contains, CLASS_LIMIT - found.size(), target.relatedRemaining(found), output));
                } catch (IllegalArgumentException | IndexOutOfBoundsException | ArithmeticException malformed) {
                    output.write("[" + source + "] DEX format unsupported or incomplete\n");
                }
            }
        }
    }
}
