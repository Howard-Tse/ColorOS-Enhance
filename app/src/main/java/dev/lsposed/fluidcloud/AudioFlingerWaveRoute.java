package dev.lsposed.fluidcloud;

import java.util.ArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Positive allow-list of a live ordinary mixer track, never a capability guess. */
final class AudioFlingerWaveRoute {
    record Assessment(MediaWaveCapture.Route route,String reason,int samplingRateHz) {
        Assessment(MediaWaveCapture.Route route,String reason){this(route,reason,0);}
    }
    private static final Pattern RATE=Pattern.compile("^Sample rate: (\\d+) Hz$");
    private static final Pattern OUTPUT=Pattern.compile("^Output thread .*type \\d+ \\(([A-Z_]+)\\):\\s*$");
    private static final Pattern STREAM=Pattern.compile("^AudioStreamOut:\\s+(\\S+)\\s+flags\\s+(0[xX][\\da-fA-F]+|0)\\s*(?:\\([^\\r\\n]*\\))?\\s*$");
    private static final Pattern TRACK=Pattern.compile("^\\s*(?:(F\\d+)\\s+)?(?:([A-Za-z?]+)\\s+)?\\d+\\s+(yes|no)\\s+(\\d+)\\s*/\\s*(\\d+)\\s+(\\d+)(?:\\s+(.*))?$");
    private static final Pattern DETAILS=Pattern.compile("^\\d+\\s+(\\S+)\\s+(0[xX][\\da-fA-F]+)\\s+([\\da-fA-F]{8})\\s+.*$");
    private static final Pattern PERFECT=Pattern.compile(".*\\s[A-Za-z]\\s+\\d+\\s+\\d+\\s+(true|false)\\s+(true|false)\\s+\\S+.*");
    // AudioStreamOut::flags is audio_output_flags_t. Permit only ordinary outputs.
    private static final long ORDINARY_OUTPUT_FLAGS=0x2|0x8; // PRIMARY, DEEP_BUFFER
    // The track table's Flags column is audio_track_cblk_t::mFlags, not output flags.
    private static final long KNOWN_CBLK_FLAGS=0x7ef;
    private static final long HARMLESS_CBLK_FLAGS=0x1|0x2|0x20; // UNDERRUN, FORCEREADY, LOOP_CYCLE
    private static final class Output {
        final String type;long flags;int streams,rate,rateFields;boolean invalid;
        int effectsStart=-1,effectsEnd;
        Output(String type,int end){this.type=type;effectsEnd=end;}
        void stream(String line){
            streams++;
            Matcher value=STREAM.matcher(line);
            if(!value.matches() || value.group(1).equals("(nil)") || value.group(1).equals("0x0")){invalid=true;return;}
            try{flags=value.group(2).equals("0")?0:Long.parseLong(value.group(2).substring(2),16);}
            catch(NumberFormatException unknown){invalid=true;}
        }
    }
    private record Candidate(Output output,String fast,String kind,String details,String line) { }
    static boolean safe(String dump,int uid,int session){
        return inspect(dump,uid,session)==MediaWaveCapture.Route.SAFE;
    }
    static MediaWaveCapture.Route inspect(String dump,int uid,int session){
        return assess(dump,uid,session).route();
    }
    static Assessment assess(String dump,int uid,int session){
        return assess(dump,uid,session,false);
    }
    static Assessment assess(String dump,int uid,int session,boolean allowOutputMix){
        if(dump==null || uid<0 || session<=0 || dump.length()>262144)return new Assessment(MediaWaveCapture.Route.WAIT,"snapshot not ready");
        Output current=null;boolean tracks=false;var candidates=new ArrayList<Candidate>();
        String[] lines=dump.split("\n");
        var outputs=new ArrayList<Output>();
        int orphanStart=-1,orphanEnd=lines.length;
        boolean unknownOutput=false;
        for(int index=0;index<lines.length;index++){
            String line=lines[index];
            String trimmed=line.trim();
            if(trimmed.equals("Orphan Effect Chains")){
                if(current!=null)current.effectsEnd=Math.min(current.effectsEnd,index);
                orphanStart=index;current=null;tracks=false;
                continue;
            }
            if(orphanStart>=0 && trimmed.startsWith("Historical Thread Log"))orphanEnd=Math.min(orphanEnd,index);
            Matcher output=OUTPUT.matcher(trimmed);
            if(trimmed.startsWith("Output thread") || trimmed.startsWith("Input thread") || trimmed.startsWith("Record thread")){
                if(current!=null)current.effectsEnd=Math.min(current.effectsEnd,index);
                current=output.matches()?new Output(output.group(1),lines.length):null;
                if(allowOutputMix && trimmed.startsWith("Output thread")){
                    if(current==null)unknownOutput=true;
                    else outputs.add(current);
                }
                tracks=false;
                continue;
            }
            if(current==null)continue;
            Matcher rate=RATE.matcher(trimmed);
            if(rate.matches() && !tracks){
                current.rateFields++;
                try{current.rate=Integer.parseInt(rate.group(1));}catch(NumberFormatException invalid){current.rate=0;}
                continue;
            }
            if(trimmed.startsWith("AudioStreamOut:")){current.stream(trimmed);continue;}
            if(trimmed.matches("\\d+ Tracks(?: of which \\d+ are active)?")){tracks=true;continue;}
            if(trimmed.endsWith(" Effect Chains") && index<current.effectsEnd){
                current.effectsStart=current.effectsStart==-1?index:-2;
                tracks=false;
            }
            if(trimmed.startsWith("Local log:")){
                current.effectsEnd=Math.min(current.effectsEnd,index);
                tracks=false;
            }
            if(!tracks)continue;
            Matcher row=TRACK.matcher(line);
            if(!row.matches())continue;
            try{
                if(Integer.parseInt(row.group(5))!=uid || Integer.parseInt(row.group(6))!=session)continue;
                // Old inactive AudioTracks can outlive their replacements in the same
                // session. Only the live output describes the route we may capture.
                if(!row.group(3).equals("yes"))continue;
                candidates.add(new Candidate(current,row.group(1),row.group(2),row.group(7),line));
            }catch(NumberFormatException invalid){return new Assessment(MediaWaveCapture.Route.WAIT,"track values not ready");}
        }
        // Finish each thread block before judging it: stream metadata must not leak
        // across outputs, and a later contradictory field must still fail closed.
        Assessment waiting=null,safe=null;
        for(Candidate candidate:candidates){
            Assessment result=assess(candidate);
            if(result.route()==MediaWaveCapture.Route.UNSAFE)return result;
            if(result.route()==MediaWaveCapture.Route.WAIT){if(waiting==null)waiting=result;}
            else safe=result;
        }
        if(waiting!=null)return waiting;
        if(candidates.size()!=1)return new Assessment(MediaWaveCapture.Route.WAIT,candidates.isEmpty()?"live track not ready":"ambiguous live tracks");
        Output selected=candidates.getFirst().output();
        var effects=AudioFlingerWaveEffects.inspect(lines,selected.effectsStart,selected.effectsEnd,session);
        if(effects!=AudioFlingerWaveEffects.Result.CLEAR && effects!=AudioFlingerWaveEffects.Result.UNKNOWN
                && allowOutputMix && !unknownOutput && outputMixAvailable(lines,outputs,orphanStart,orphanEnd)){
            return new Assessment(MediaWaveCapture.Route.SAFE_OUTPUT_MIX,"audio effect compatibility: output mix");
        }
        return switch(effects){
            case CLEAR -> safe;
            case UNKNOWN -> new Assessment(MediaWaveCapture.Route.WAIT,"audio effect state not ready");
            case GLOBAL -> new Assessment(MediaWaveCapture.Route.EFFECT_CONFLICT,"global audio effect active");
            case SESSION -> new Assessment(MediaWaveCapture.Route.EFFECT_CONFLICT,"playback session audio effect active");
            case VISUALIZER_IN_USE -> new Assessment(MediaWaveCapture.Route.EFFECT_CONFLICT,"playback session visualizer in use");
        };
    }
    private static boolean outputMixAvailable(String[] lines,ArrayList<Output> outputs,int orphanStart,int orphanEnd){
        // AudioPolicy chooses the mix output and can move the global chain between threads.
        for(Output output:outputs)
            if(AudioFlingerWaveEffects.inspectOutputMix(lines,output.effectsStart,output.effectsEnd)
                    !=AudioFlingerWaveEffects.Result.CLEAR)return false;
        return orphanStart<0 || AudioFlingerWaveEffects.inspectOutputMix(lines,orphanStart,orphanEnd)
                ==AudioFlingerWaveEffects.Result.CLEAR;
    }
    private static Assessment assess(Candidate track){
        Output output=track.output();
        if(!output.type.equals("MIXER"))return new Assessment(MediaWaveCapture.Route.UNSAFE,"output="+output.type);
        if(track.fast()!=null)return new Assessment(MediaWaveCapture.Route.UNSAFE,"fast track");
        if(track.kind()!=null)return new Assessment(MediaWaveCapture.Route.WAIT,"track type not ordinary");
        if(output.streams!=1 || output.invalid)return new Assessment(MediaWaveCapture.Route.WAIT,"output flags not ready");
        if((output.flags&~ORDINARY_OUTPUT_FLAGS)!=0)return new Assessment(MediaWaveCapture.Route.UNSAFE,"output flags=0x"+Long.toHexString(output.flags));
        Matcher detail=DETAILS.matcher(track.details()==null?"":track.details());
        if(!detail.matches())return new Assessment(MediaWaveCapture.Route.WAIT,"track columns not ready");
        try{
            long cblk=Long.parseLong(detail.group(2).substring(2),16);
            if((cblk&~KNOWN_CBLK_FLAGS)!=0)return new Assessment(MediaWaveCapture.Route.WAIT,"unknown cblk=0x"+Long.toHexString(cblk));
            if((cblk&~HARMLESS_CBLK_FLAGS)!=0)return new Assessment(MediaWaveCapture.Route.WAIT,"cblk not ready=0x"+Long.toHexString(cblk));
            long format=Long.parseLong(detail.group(3),16);
            if(format<1 || format>6)return new Assessment(MediaWaveCapture.Route.UNSAFE,"format=0x"+Long.toHexString(format));
            Matcher perfect=PERFECT.matcher(track.line());
            if(!perfect.matches())return new Assessment(MediaWaveCapture.Route.WAIT,"track columns not ready");
            if(!perfect.group(1).equals("false"))return new Assessment(MediaWaveCapture.Route.UNSAFE,"bit-perfect track");
            if(!(detail.group(1).equals("A") || detail.group(1).equals("r")))return new Assessment(MediaWaveCapture.Route.WAIT,"track state not ready");
            int rate=output.rateFields==1 && output.rate>=32000 && output.rate<=192000?output.rate:0;
            return new Assessment(MediaWaveCapture.Route.SAFE,"ordinary PCM mixer output=0x"+Long.toHexString(output.flags)+" cblk=0x"+Long.toHexString(cblk),rate);
        }catch(NumberFormatException invalid){return new Assessment(MediaWaveCapture.Route.WAIT,"track values not ready");}
    }
}
