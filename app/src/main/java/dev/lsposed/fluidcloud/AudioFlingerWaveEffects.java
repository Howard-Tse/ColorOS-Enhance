package dev.lsposed.fluidcloud;

import java.util.Locale;
import java.util.regex.Pattern;

/** Check only the effect chains on the verified playback output. */
final class AudioFlingerWaveEffects {
    private static final String VISUALIZER = "e46b26a0-dddd-11db-8afd-0002a5d5c51b";
    private static final String VOLUME = "09e8ede0-ddde-11db-b4f6-0002a5d5c51b";
    private static final String DYNAMICS = "7261676f-6d75-7369-6364-28e2fd3ac39e";
    private static final Pattern CHAINS = Pattern.compile("(\\d+) Effect Chains");
    private static final Pattern CHAIN = Pattern.compile("(\\d+) effects? for session (-?\\d+)");
    private static final Pattern EFFECT = Pattern.compile("Effect ID \\d+:");
    private static final Pattern STATE = Pattern.compile("(-?\\d+)\\s+\\d+\\s+[yn]\\s+[yn]\\s+([yn])\\s+([yn])");
    private static final Pattern TYPE = Pattern.compile("- TYPE: ([\\da-fA-F-]{36})");
    private static final Pattern CLIENTS = Pattern.compile("(\\d+) Clients?:");
    private static final Pattern FLAGS = Pattern.compile("- flags: ([\\da-fA-F]{8})(?: \\(.*\\))?");
    private static final String STATUS_HEADER = "Session State Registered Internal Enabled Suspended:";

    enum Result {
        CLEAR, UNKNOWN, GLOBAL, SESSION, VISUALIZER_IN_USE
    }

    static Result inspect(String[] lines, int start, int end, int session) {
        return inspect(lines, start, end, session, false);
    }

    static Result inspectOutputMix(String[] lines, int start, int end) {
        return inspect(lines, start, end, 0, true);
    }

    private static Result inspect(String[] lines, int start, int end, int session, boolean outputMix) {
        if (start < 0 || start >= end) return Result.UNKNOWN;
        var count = CHAINS.matcher(lines[start].trim());
        boolean orphan = outputMix && lines[start].trim().equals("Orphan Effect Chains");
        if (!orphan && !count.matches()) return Result.UNKNOWN;
        try {
            int expected = orphan ? -1 : Integer.parseInt(count.group(1));
            int found = 0;
            for (int i = start + 1; i < end; i++) {
                var chain = CHAIN.matcher(lines[i].trim());
                if (!chain.matches()) continue;
                found++;
                int owner = Integer.parseInt(chain.group(2));
                if (owner != 0 && owner != session) continue;
                int limit = nextHeader(CHAIN, lines, i + 1, end);
                Result result = inspectChain(lines, i + 1, limit, owner, Integer.parseInt(chain.group(1)), outputMix);
                if (result != Result.CLEAR) return result;
                i = limit - 1;
            }
            return (orphan ? found > 0 : found == expected) ? Result.CLEAR : Result.UNKNOWN;
        } catch (NumberFormatException malformed) {
            return Result.UNKNOWN;
        }
    }

    private static Result inspectChain(String[] lines, int start, int end, int owner, int expected, boolean outputMix) {
        int found = 0;
        for (int i = nextHeader(EFFECT, lines, start, end); i < end;) {
            int limit = nextHeader(EFFECT, lines, i + 1, end);
            Result result = inspectEffect(lines, i + 1, limit, owner, outputMix);
            if (result != Result.CLEAR) return result;
            found++;
            i = limit;
        }
        return found == expected ? Result.CLEAR : Result.UNKNOWN;
    }

    private static Result inspectEffect(String[] lines, int start, int end, int owner, boolean outputMix) {
        String type = null;
        long flags = -1;
        int clients = -1;
        boolean hasState = false, active = false;
        for (int i = start; i < end; i++) {
            String line = lines[i].trim();
            if (line.equals(STATUS_HEADER) && i + 1 < end) {
                var state = STATE.matcher(lines[++i].trim());
                if (!state.matches() || Integer.parseInt(state.group(1)) != owner || hasState) return Result.UNKNOWN;
                hasState = true;
                active = state.group(2).equals("y") || state.group(3).equals("y");
            } else if (line.startsWith("- TYPE:")) {
                var value = TYPE.matcher(line);
                if (!value.matches() || type != null) return Result.UNKNOWN;
                type = value.group(1).toLowerCase(Locale.ROOT);
            } else if (line.startsWith("- flags:")) {
                var value = FLAGS.matcher(line);
                if (!value.matches() || flags >= 0) return Result.UNKNOWN;
                flags = Long.parseLong(value.group(1), 16);
            } else if (line.endsWith(" Clients:") || line.endsWith(" Client:")) {
                var value = CLIENTS.matcher(line);
                if (!value.matches() || clients >= 0) return Result.UNKNOWN;
                clients = Integer.parseInt(value.group(1));
            }
        }
        if (!hasState || type == null || flags < 0) return Result.UNKNOWN;
        if (owner == 0 && !outputMix) {
            // AudioFlinger exempts auxiliary, visualization, volume and dynamics effects on session 0.
            boolean exempt = (flags & 7) == 1 || type.equals(VISUALIZER) || type.equals(VOLUME) || type.equals(DYNAMICS);
            return active && !exempt ? Result.GLOBAL : Result.CLEAR;
        }
        if (type.equals(VISUALIZER)) {
            if (active || clients > 0) return Result.VISUALIZER_IN_USE;
            return clients == 0 ? Result.CLEAR : Result.UNKNOWN;
        }
        if (outputMix) return Result.CLEAR;
        // Includes per-player processors and the original-track mute used by RootlessJamesDSP.
        return active ? Result.SESSION : Result.CLEAR;
    }

    private static int nextHeader(Pattern pattern, String[] lines, int start, int end) {
        for (int i = start; i < end; i++) if (pattern.matcher(lines[i].trim()).matches()) return i;
        return end;
    }

    private AudioFlingerWaveEffects() { }
}
