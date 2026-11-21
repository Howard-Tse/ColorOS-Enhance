package dev.lsposed.fluidcloud;

import java.io.IOException;
import java.io.Writer;
import java.nio.ByteBuffer;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.function.Predicate;

/** Exports selected DEX declarations and symbolic instructions without loading firmware classes. */
public final class FirmwareDexReport {
    private static final int MAX_SCAN_UNITS = 8192;
    private static final int MAX_REFERENCES = 32;
    private static final int MAX_MEMBERS = 160;
    private final DexFieldUse dex;
    private final ByteBuffer data;
    private final Writer output;

    private FirmwareDexReport(byte[] bytes, Writer output) {
        try { dex = new DexFieldUse(bytes); }
        catch (IOException invalid) { throw new IllegalArgumentException("Unsupported DEX", invalid); }
        data = dex.data;
        if (data.getInt(36) != 112) throw new IllegalArgumentException("Unsupported DEX header");
        this.output = output;
    }

    public static Set<String> write(byte[] bytes, String entry, Predicate<String> select,
            Predicate<String> trace, int classLimit, int relatedLimit, Writer output) throws IOException {
        return new FirmwareDexReport(bytes, output).write(entry, select, trace, classLimit, relatedLimit);
    }

    private Set<String> write(String entry, Predicate<String> select, Predicate<String> trace, int limit, int relatedLimit)
            throws IOException {
        Set<String> found = new LinkedHashSet<>();
        int classes = data.getInt(96), offset = data.getInt(100);
        for (int i = 0; i < classes && found.size() < limit; i++) {
            int definition = offset + i * 32;
            String descriptor = dex.type(data.getInt(definition));
            String name = descriptor.substring(1, descriptor.length() - 1).replace('/', '.');
            if (!select.test(name)) continue;
            boolean traceBody = trace.test(name);
            if (!traceBody && relatedLimit == 0) continue;
            if (!traceBody) relatedLimit--;
            if (found.isEmpty()) output.write("\n[" + entry + "]\n");
            found.add(name);
            writeClass(definition, descriptor, traceBody);
        }
        return found;
    }

    private void writeClass(int definition, String owner, boolean trace) throws IOException {
        int parent = data.getInt(definition + 8);
        output.write("\n" + owner + " flags=0x" + Integer.toHexString(data.getInt(definition + 4))
                + (parent == -1 ? "" : " extends " + dex.type(parent))
                + " interfaces=" + types(data.getInt(definition + 12)) + "\n");
        int classData = data.getInt(definition + 24);
        if (classData == 0) return;
        int[] cursor = {classData};
        int staticFields = dex.uleb(cursor), instanceFields = dex.uleb(cursor);
        int directMethods = dex.uleb(cursor), virtualMethods = dex.uleb(cursor);
        for (int group : new int[] {staticFields, instanceFields}) {
            int index = 0;
            for (int i = 0; i < group; i++) {
                index += dex.uleb(cursor);
                int flags = dex.uleb(cursor);
                if (i < MAX_MEMBERS) output.write("  field 0x" + Integer.toHexString(flags) + " " + field(index) + "\n");
            }
        }
        for (int group : new int[] {directMethods, virtualMethods}) {
            int index = 0;
            for (int i = 0; i < group; i++) {
                index += dex.uleb(cursor);
                int flags = dex.uleb(cursor), code = dex.uleb(cursor);
                if (i >= MAX_MEMBERS) continue;
                int units = code == 0 ? 0 : data.getInt(code + 12);
                output.write("  method 0x" + Integer.toHexString(flags) + " " + method(index)
                        + " code_units=" + units + "\n");
                if (trace && units != 0) writeReferences(code + 16, units);
            }
        }
        if (Math.max(Math.max(staticFields, instanceFields), Math.max(directMethods, virtualMethods)) > MAX_MEMBERS)
            output.write("  [member limit reached]\n");
    }

    private void writeReferences(int start, int units) throws IOException {
        Set<String> references = new LinkedHashSet<>();
        int unit = 0;
        while (unit < units && unit < MAX_SCAN_UNITS && references.size() < MAX_REFERENCES) {
            int opcode = dex.u16(start + unit * 2) & 255;
            int width = DexFieldUse.width(data, start, unit);
            if (width <= 0 || width > units - unit) throw new IllegalArgumentException("Invalid DEX code");
            String reference = reference(start + unit * 2, opcode);
            if (reference != null && references.add(reference))
                output.write("    @" + Integer.toHexString(unit) + " " + reference + "\n");
            unit += width;
        }
        if (unit < units) output.write("    [instruction scan limit reached]\n");
    }

    private String reference(int start, int opcode) {
        if (opcode >= 0x52 && opcode <= 0x6d)
            return "field[" + Integer.toHexString(opcode) + "] " + field(dex.u16(start + 2));
        if (opcode >= 0x6e && opcode <= 0x72 || opcode >= 0x74 && opcode <= 0x78
                || opcode == 0xfa || opcode == 0xfb)
            return "invoke[" + Integer.toHexString(opcode) + "] " + method(dex.u16(start + 2));
        if (opcode == 0x1c || opcode == 0x1f || opcode == 0x20 || opcode >= 0x22 && opcode <= 0x25)
            return "type[" + Integer.toHexString(opcode) + "] " + dex.type(dex.u16(start + 2));
        return null;
    }

    private String field(int index) {
        int item = dex.fields + index * 8;
        return dex.type(dex.u16(item)) + "->" + dex.string(data.getInt(item + 4)) + ":" + dex.type(dex.u16(item + 2));
    }

    private String method(int index) {
        int item = dex.methods + index * 8;
        int proto = dex.protos + dex.u16(item + 2) * 12;
        return dex.type(dex.u16(item)) + "->" + dex.string(data.getInt(item + 4)) + "("
                + types(data.getInt(proto + 8)) + ")" + dex.type(data.getInt(proto + 4));
    }

    private String types(int offset) {
        if (offset == 0) return "";
        StringBuilder descriptor = new StringBuilder();
        int count = data.getInt(offset);
        for (int i = 0; i < count; i++) descriptor.append(dex.type(dex.u16(offset + 4 + i * 2)));
        return descriptor.toString();
    }
}
