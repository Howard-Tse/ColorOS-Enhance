package dev.howard.colorosenhance.diagnostics;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.StringWriter;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public final class DiagnosticInterfacesTest {
    @Rule public TemporaryFolder files = new TemporaryFolder();

    @Test public void exportsOnlySelectedDeclarationsAndReferencesAndSkipsUnreadableDex() throws Exception {
        File apk = files.newFile("fixture.apk");
        try (var zip = new ZipOutputStream(Files.newOutputStream(apk.toPath()))) {
            zip.putNextEntry(new ZipEntry("classes.dex"));
            zip.write(new byte[12]);
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("classes2.dex"));
            zip.write(fixture());
            zip.closeEntry();
        }
        var found = new LinkedHashSet<String>();
        var output = new StringWriter();
        var target = new DiagnosticInterfaces.Target("demo", List.of("demo.Capsule"), List.of());
        DiagnosticInterfaces.writeApk(apk, "base", target, found, output);
        String report = output.toString();
        assertEquals(Set.of("demo.Capsule"), found);
        assertTrue(report.contains("[base/classes.dex] DEX format unsupported or incomplete"));
        assertTrue(report.contains("[base/classes2.dex]"));
        assertTrue(report.contains("field 0x1 Ldemo/Capsule;->w:I"));
        assertTrue(report.contains("method 0x1 Ldemo/Capsule;->bind()V code_units=8"));
        assertTrue(report.contains("field[60] Ldemo/Capsule;->w:I"));
        assertTrue(report.contains("invoke[71] Lother/Source;->update()V"));
        assertFalse(report.contains("PRIVATE_LITERAL"));
        assertFalse(report.contains(apk.getAbsolutePath()));
    }

    @Test public void detectsModernAndLegacyColorOsPropertiesWithoutInferringVersionFromAndroid() {
        Map<String, String> properties = Map.of("ro.build.version.oplusrom", " V17.0.0 \nignored",
                "ro.build.version.opporom", "V16.0", "ro.build.version.ota", "PLG110_17.0.0.201");
        var modern = DiagnosticEnvironment.versions(properties::get);
        assertEquals("V17.0.0", modern.colorOs());
        assertEquals("PLG110_17.0.0.201", modern.ota());
        assertEquals("V16.0", DiagnosticEnvironment.versions(key ->
                key.equals("ro.build.version.oplusrom") ? "" : properties.get(key)).colorOs());
        assertEquals("", DiagnosticEnvironment.versions(key -> null).colorOs());
    }

    @Test public void loadedSeedlingArchiveProvidesVersionAndOptionalScopedReferences() throws Exception {
        File archive = files.newFile("loaded-sdk.apk");
        try (var zip = new ZipOutputStream(Files.newOutputStream(archive.toPath()))) {
            zip.putNextEntry(new ZipEntry("classes.dex"));
            zip.write(fixture("Lcom/oplus/seedling/framework/manager/SeedlingSdkInternal;"));
            zip.closeEntry();
        }
        var report = SeedlingSdkDiagnostics.fromArchive(archive, "13.1.1", 13001001,
                SeedlingSdkDiagnostics.Status.LOADED, true);
        assertTrue(report.loaded());
        assertEquals("13.1.1 (13001001)", DiagnosticEnvironment.version(report.version(), report.code()));
        assertEquals(1, report.classes());
        assertTrue(report.interfaces().contains("SeedlingSdkInternal;->bind()V"));
        assertTrue(report.interfaces().contains("invoke[71] Lother/Source;->update()V"));
        assertFalse(report.interfaces().contains(archive.getAbsolutePath()));

        var versionOnly = SeedlingSdkDiagnostics.fromArchive(archive, "13.1.1", 13001001,
                SeedlingSdkDiagnostics.Status.LOADED, false);
        assertTrue(versionOnly.loaded());
        assertEquals(report.version(), versionOnly.version());
        assertEquals(report.code(), versionOnly.code());
        assertTrue(versionOnly.interfaces().isEmpty());

        var prepared = SeedlingSdkDiagnostics.fromArchive(archive, "13.1.1", 13001001,
                SeedlingSdkDiagnostics.Status.PROVISIONED, true);
        assertTrue(prepared.available());
        assertFalse(prepared.loaded());
        assertEquals(report.interfaces(), prepared.interfaces());
    }

    private static byte[] fixture() {
        return fixture("Ldemo/Capsule;");
    }

    private static byte[] fixture(String owner) {
        ByteBuffer data = ByteBuffer.allocate(1024).order(ByteOrder.LITTLE_ENDIAN);
        data.put("dex\n039\0".getBytes(StandardCharsets.US_ASCII));
        data.putInt(32, data.capacity()).putInt(36, 112).putInt(40, 0x12345678);
        data.putInt(60, 112).putInt(68, 160).putInt(76, 184).putInt(84, 200)
                .putInt(92, 208).putInt(96, 1).putInt(100, 256);
        String[] strings = {owner, "Lother/Source;", "Ljava/lang/Object;", "V", "I",
                "w", "bind", "update", "PRIVATE_LITERAL"};
        data.position(512);
        for (int i = 0; i < strings.length; i++) {
            data.putInt(112 + i * 4, data.position());
            byte[] text = strings[i].getBytes(StandardCharsets.US_ASCII);
            data.put((byte) text.length).put(text).put((byte) 0);
        }
        for (int i = 0; i < 5; i++) data.putInt(160 + i * 4, i);
        data.putInt(188, 3); // ()V prototype.
        data.putShort(202, (short) 4).putInt(204, 5); // Capsule.w:I.
        data.putInt(212, 6).putShort(216, (short) 1).putInt(220, 7); // Capsule.bind, Source.update.
        data.putInt(260, 1).putInt(264, 2).putInt(280, 320);
        data.position(320);
        data.put(new byte[]{1, 0, 1, 0, 0, 1, 0, 1, (byte) 0x80, 3});
        data.putInt(396, 8);
        data.position(400);
        for (int unit : new int[]{0x60, 0, 0x71, 1, 0, 0x1a, 8, 0x0e}) data.putShort((short) unit);
        return data.array();
    }
}
