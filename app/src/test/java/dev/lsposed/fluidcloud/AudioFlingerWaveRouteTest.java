package dev.lsposed.fluidcloud;

import org.junit.Test;
import static org.junit.Assert.*;

public class AudioFlingerWaveRouteTest {
    @Test public void captureRateBelongsToTheVerifiedOutputNotTheResampledTrackOrAnotherThread(){
        String rateHeader=HEADER.replace("  1 Tracks", " Sample rate: 48000 Hz\n  1 Tracks");
        assertEquals(48000,assess(rateHeader+ROW,10352,3673).samplingRateHz());
        assertEquals(0,assess(HEADER+ROW,10352,3673).samplingRateHz());
        assertEquals(0,assess(rateHeader.replace("48000","invalid")+ROW,10352,3673).samplingRateHz());
        assertEquals(0,assess(rateHeader.replace("MIXER","OFFLOAD")+ROW,10352,3673).samplingRateHz());
        String other=rateHeader.replace("48000","96000").replace("AudioOut_15","AudioOut_20");
        assertEquals(48000,assess(other+ROW.replace("3673","3674")+rateHeader+ROW,10352,3673).samplingRateHz());
    }
    private static final String STREAM=" AudioStreamOut: 0x456 flags 0x8 (AUDIO_OUTPUT_FLAG_DEEP_BUFFER)\n";
    private static final String HEADER="Output thread 0x123, name AudioOut_15, tid 10, type 0 (MIXER):\n"+STREAM+"  1 Tracks of which 1 are active\n"
            +" Type Id Active Client(pid/uid) Session Port Id S Flags Format Chn mask SRate ST Usg CT G db L dB R dB VS dB PortVol dB PortMuted Server FrmCnt FrmRdy F Underruns Flushed BitPerfect InternalMute Latency\n";
    private static final String ROW=" 1245 yes 100/ 10352 3673 1311 A 0x000 00000001 00000003 44100 3 1 2 0 0 0 0 0 false 00000000 2058 0 f 0 0 false false 10\n";
    private static final String JDSP = "f98765f4-c321-5de6-9a45-123459495ab2";
    private static final String VISUALIZER = "e46b26a0-dddd-11db-8afd-0002a5d5c51b";
    private static final String DYNAMICS = "7261676f-6d75-7369-6364-28e2fd3ac39e";
    private static final String VOLUME = "09e8ede0-ddde-11db-b4f6-0002a5d5c51b";
    private static final String OPLUS_AUDIO_X = "41f6c0f4-5d8f-11ec-bf63-0242ac130002";

    // Earlier route fixtures omit the effect table. Supply the native empty table.
    private static AudioFlingerWaveRoute.Assessment assess(String dump, int uid, int session) {
        if (dump != null && !dump.contains("Effect Chains") && !dump.contains("Local log:"))
            dump += "  0 Effect Chains\n";
        return AudioFlingerWaveRoute.assess(dump, uid, session);
    }
    private static MediaWaveCapture.Route inspect(String dump, int uid, int session) {
        return assess(dump, uid, session).route();
    }
    private static boolean safe(String dump, int uid, int session) {
        return inspect(dump, uid, session) == MediaWaveCapture.Route.SAFE;
    }
    private static String chain(int session, String type, boolean enabled, boolean suspended, int clients) {
        return "    1 effects for session " + session + "\n"
                + "        Effect ID 35:\n"
                + "            Session State Registered Internal Enabled Suspended:\n"
                + "            " + session + " 003 y n " + (enabled ? "y" : "n") + " " + (suspended ? "y" : "n") + "\n"
                + "            Descriptor:\n"
                + "            - TYPE: " + type + "\n"
                + "            - flags: 00000208 (conn. mode: insert, insert pref: first)\n"
                + "            " + clients + " Clients:\n"
                + "        Effect ID 35 HAL dump:\n";
    }
    @Test public void protectEnabledOrSuspendedGlobalProcessorsOnlyOnTheActualPlaybackOutput() {
        String enabled = "  1 Effect Chains\n" + chain(0, JDSP, true, false, 1);
        String suspended = "  1 Effect Chains\n" + chain(0, JDSP, false, true, 1);
        assertEquals("global audio effect active", assess(HEADER+ROW+enabled,10352,3673).reason());
        assertEquals(MediaWaveCapture.Route.EFFECT_CONFLICT, inspect(HEADER+ROW+suspended,10352,3673));
        assertTrue(safe(HEADER+enabled+HEADER+ROW+"  0 Effect Chains\n",10352,3673));
        assertTrue(safe(HEADER+ROW+"  1 Effect Chains\n"+chain(0,JDSP,false,false,1),10352,3673));
    }
    private static String stockAudioX(boolean enabled, boolean suspended) {
        return chain(0,OPLUS_AUDIO_X,enabled,suspended,1)
                .replace("Descriptor:\n","Descriptor:\n            - UUID: "+OPLUS_AUDIO_X+"\n")
                .replace("1 Clients:","- name: OplusAudioX\n            - implementor: Oplus Holdings Corp.\n            1 Clients:");
    }
    @Test public void verifiedStockColorOsAudioXAllowsPrivateCaptureWithCompatibilityOffOrOn() {
        String snapshot=HEADER+ROW+"  1 Effect Chains\n"+stockAudioX(true,false);
        assertEquals(MediaWaveCapture.Route.SAFE,AudioFlingerWaveRoute.assess(snapshot,10352,3673,false).route());
        assertEquals(MediaWaveCapture.Route.SAFE,AudioFlingerWaveRoute.assess(snapshot,10352,3673,true).route());
    }
    @Test public void audioXExemptionDoesNotCoverOtherDriversOrOtherEffectsInTheSameChain() {
        String stock=stockAudioX(true,false);
        String head=HEADER+ROW+"  1 Effect Chains\n";
        for(String driver:new String[]{stock.replace("Oplus Holdings Corp.","Other vendor"),
                stock.replace("- UUID: "+OPLUS_AUDIO_X,"- UUID: "+JDSP),
                stock.replace("00000208","00000008"),stockAudioX(false,true)}){
            assertEquals(MediaWaveCapture.Route.EFFECT_CONFLICT,inspect(head+driver,10352,3673));
        }
        String second=chain(0,JDSP,true,false,1).split("\n",2)[1].replace("Effect ID 35","Effect ID 80");
        String combined=stock.replace("1 effects for session 0","2 effects for session 0")+second;
        assertEquals(MediaWaveCapture.Route.EFFECT_CONFLICT,inspect(head+combined,10352,3673));
        assertEquals(MediaWaveCapture.Route.SAFE_OUTPUT_MIX,AudioFlingerWaveRoute.assess(head+combined,10352,3673,true).route());
    }
    @Test public void standardExemptionsApplyOnlyToTheGlobalOutputMix() {
        for (String type : new String[]{VISUALIZER, DYNAMICS, VOLUME})
            assertTrue(safe(HEADER+ROW+"  1 Effect Chains\n"+chain(0,type,true,false,1),10352,3673));
        assertTrue(safe(HEADER+ROW+"  1 Effect Chains\n"+chain(0,JDSP,true,false,1)
                .replace("00000208 (conn. mode: insert","00000001 (conn. mode: auxiliary"),10352,3673));
        for (String type : new String[]{JDSP, DYNAMICS, VOLUME})
            assertEquals("playback session audio effect active",
                    assess(HEADER+ROW+"  1 Effect Chains\n"+chain(3673,type,true,false,1),10352,3673).reason());
        assertTrue(safe(HEADER+ROW+"  1 Effect Chains\n"+chain(3674,JDSP,true,false,1),10352,3673));
    }
    @Test public void doNotTakeControlOfAnotherVisualizerEvenWhenItIsDisabled() {
        assertEquals("playback session visualizer in use",
                assess(HEADER+ROW+"  1 Effect Chains\n"+chain(3673,VISUALIZER,false,false,1),10352,3673).reason());
        assertTrue(safe(HEADER+ROW+"  1 Effect Chains\n"+chain(3673,VISUALIZER,false,false,0),10352,3673));
    }
    @Test public void onlyOptedInConflictsUseTheOutputMix() {
        String plain=HEADER+ROW+"  0 Effect Chains\n";
        assertEquals(MediaWaveCapture.Route.SAFE,AudioFlingerWaveRoute.assess(plain,10352,3673,true).route());
        for(int session:new int[]{0,3673}){
            String effect=HEADER+ROW+"  1 Effect Chains\n"+chain(session,JDSP,true,false,1);
            assertEquals(MediaWaveCapture.Route.EFFECT_CONFLICT,AudioFlingerWaveRoute.assess(effect,10352,3673,false).route());
            assertEquals(MediaWaveCapture.Route.SAFE_OUTPUT_MIX,AudioFlingerWaveRoute.assess(effect,10352,3673,true).route());
            assertEquals(MediaWaveCapture.Route.UNSAFE,
                    AudioFlingerWaveRoute.assess(effect.replace("MIXER","OFFLOAD"),10352,3673,true).route());
        }
    }
    @Test public void outputMixChecksOtherOutputsAndOrphanedEffectsForExistingVisualizerClients() {
        String conflict=HEADER+ROW+"  1 Effect Chains\n"+chain(3673,JDSP,true,false,1);
        String mixReader=chain(0,VISUALIZER,false,false,1);
        for(String suffix:new String[]{HEADER+"  1 Effect Chains\n"+mixReader,
                "  Orphan Effect Chains\n"+mixReader,
                HEADER}){
            assertEquals(MediaWaveCapture.Route.EFFECT_CONFLICT,
                    AudioFlingerWaveRoute.assess(conflict+suffix,10352,3673,true).route());
        }
        String idleReader=chain(0,VISUALIZER,false,false,0);
        assertEquals(MediaWaveCapture.Route.SAFE_OUTPUT_MIX,
                AudioFlingerWaveRoute.assess(conflict+"  Orphan Effect Chains\n"+idleReader,10352,3673,true).route());
    }
    @Test public void missingAndIncompleteEffectTablesWaitWithoutUsingAnotherOutputOrHistory() {
        assertEquals(MediaWaveCapture.Route.WAIT, AudioFlingerWaveRoute.inspect(HEADER+ROW,10352,3673));
        assertEquals(MediaWaveCapture.Route.WAIT, inspect(HEADER+ROW+"  1 Effect Chains\n"
                +HEADER+"  1 Effect Chains\n"+chain(0,JDSP,true,false,1),10352,3673));
        assertEquals(MediaWaveCapture.Route.WAIT, inspect(HEADER+ROW+"  1 Effect Chains\n"
                +chain(0,JDSP,false,false,1).replace("0 003 y n n n","bad state"),10352,3673));
        assertEquals(MediaWaveCapture.Route.WAIT, inspect(HEADER+ROW+"  1 Effect Chains\n"
                +"    2 effects for session 0\n"+chain(0,JDSP,false,false,1).split("\n",2)[1],10352,3673));
        assertTrue(safe(HEADER+ROW+"  0 Effect Chains\n  Local log:\n"+chain(0,JDSP,true,false,1),10352,3673));
    }
    @Test public void onlyAPositiveLivePrivateOrdinaryPcmTrackIsAllowed(){
        assertTrue(safe(HEADER+ROW,10352,3673));
        assertFalse(safe(HEADER+ROW,10351,3673));
        assertFalse(safe(HEADER+ROW,10352,0));
        assertFalse(safe(HEADER+ROW.replace("yes","no"),10352,3673));
    }
    @Test public void fillingStateColumnDoesNotChangeTheBitPerfectOrRouteSafetyDecision(){
        for(String state:new String[]{"A","F","f"})
            assertTrue(state,safe(HEADER+ROW.replace(" 0 f 0 0 false"," 0 "+state+" 0 0 false"),10352,3673));
    }
    @Test public void resumingOrdinaryMixerTrackKeepsTheSameSafetyConstraints(){
        String resuming=ROW.replace("1311 A ","1311 r ");
        assertTrue(safe(HEADER+resuming,10352,3673));
        assertFalse(safe(HEADER.replace("MIXER","OFFLOAD")+resuming,10352,3673));
        assertFalse(safe(HEADER+resuming.replace("0x000","0x004"),10352,3673));
        assertFalse(safe(HEADER+resuming.replace("yes","no"),10352,3673));
        for(String state:new String[]{"p","P","S","I","T","x"})
            assertFalse(safe(HEADER+ROW.replace("1311 A ","1311 "+state+" "),10352,3673));
    }
    @Test public void offloadDirectMmapAndSpatializedOrUnknownThreadsAreRejectedBeforeCreatingEffects(){
        for(String type:new String[]{"OFFLOAD","DIRECT","MMAP_PLAYBACK","SPATIALIZER","VENDOR","DUPLICATING"})
            assertFalse(type,safe(HEADER.replace("MIXER",type)+ROW,10352,3673));
        assertFalse(safe("Tracks\n"+ROW,10352,3673));
    }
    @Test public void fastRawDirectAndUnrecognizedOutputFlagsNeverBecomeABestGuess(){
        for(String flags:new String[]{"0x4","0x1","0x10","0x100","0x4000","0x100000","0xdeadbeef"})
            assertEquals(flags,MediaWaveCapture.Route.UNSAFE,inspect(HEADER.replace("flags 0x8","flags "+flags)+ROW,10352,3673));
        assertFalse(safe(HEADER+ROW.replace("00000001","08000000"),10352,3673));
        assertFalse(safe(HEADER+ROW.replace("false false 10","true false 10"),10352,3673));
    }
    @Test public void duplicatesHistoryAndInputTracksCannotPassAsACurrentPlaybackRoute(){
        assertFalse(safe(HEADER+ROW+ROW,10352,3673));
        assertFalse(safe(HEADER+" Local log:\n"+ROW,10352,3673));
        assertFalse(safe(HEADER+" 0 Effect Chains\n"+ROW,10352,3673));
        assertFalse(safe("Input thread test\n"+HEADER.substring(HEADER.indexOf("  1 Tracks"))+ROW,10352,3673));
        assertFalse(safe(null,10352,3673));
        assertFalse(safe(HEADER+ROW.replace("false false 10",""),10352,3673));
    }
    @Test public void staleInactiveTrackDoesNotRejectItsLiveReplacementInTheSamePrivateSession(){
        String old=ROW.replace("1245 yes","1244 no").replace("1311 A","1310 S");
        assertTrue(safe(HEADER+old+ROW,10352,3673));
        assertTrue(safe(HEADER+ROW+old,10352,3673));
        assertEquals(MediaWaveCapture.Route.WAIT,inspect(HEADER+old,10352,3673));
    }
    @Test public void knownUnsafeOutputsAreSeparatedFromAStartupSnapshotThatIsNotReady(){
        assertEquals(MediaWaveCapture.Route.UNSAFE,inspect(HEADER.replace("MIXER","OFFLOAD")+ROW,10352,3673));
        assertEquals(MediaWaveCapture.Route.UNSAFE,inspect(HEADER.replace("flags 0x8","flags 0x4")+ROW,10352,3673));
        assertEquals(MediaWaveCapture.Route.WAIT,inspect(HEADER+ROW.replace("0x000","0x004"),10352,3673));
        assertEquals(MediaWaveCapture.Route.WAIT,inspect(null,10352,3673));
        assertEquals(MediaWaveCapture.Route.WAIT,inspect(HEADER,10352,3673));
    }
    @Test public void currentBilibiliUnderrunIsAControlBlockStatusNotAnUnsafeOutputRoute(){
        String row="5702 yes 19248/ 10350 16841 6118 A 0x001 00000001 00000003 44100 3 1 0 -1.5 0 0 0 -1.5 false 0016AE80 26390 26390 A 1881 0 false false 1241.78 t 0 1 4 0 0\n";
        var assessment=assess(HEADER+row,10350,16841);
        assertEquals(MediaWaveCapture.Route.SAFE,assessment.route());
        assertEquals("ordinary PCM mixer output=0x8 cblk=0x1",assessment.reason());
        assertEquals(MediaWaveCapture.Route.WAIT,assess(HEADER+row.replace("0x001","0x004"),10350,16841).route());
    }
    @Test public void diagnosticsExplainRouteRejectionWithoutIncludingClientIdentity(){
        assertEquals("output=OFFLOAD",assess(HEADER.replace("MIXER","OFFLOAD")+ROW,10352,3673).reason());
        assertEquals("output flags=0x4",assess(HEADER.replace("flags 0x8","flags 0x4")+ROW,10352,3673).reason());
        assertEquals("cblk not ready=0x4",assess(HEADER+ROW.replace("0x000","0x004"),10352,3673).reason());
        assertEquals("bit-perfect track",assess(HEADER+ROW.replace("false false 10","true false 10"),10352,3673).reason());
    }
    @Test public void onlyNonePrimaryAndDeepBufferOutputFlagsOrTheirCombinationAreAllowed(){
        for(String flags:new String[]{"0","0x0","0x2","0x8","0xa"})
            assertTrue(flags,safe(HEADER.replace("flags 0x8","flags "+flags)+ROW,10352,3673));
    }
    @Test public void absentMalformedNullOrRepeatedOutputStreamFieldsFailClosed(){
        for(String stream:new String[]{"",STREAM.replace("flags 0x8","flags unknown"),STREAM.replace("0x456","(nil)"),
                STREAM+STREAM,STREAM+STREAM.replace("flags 0x8","flags 0x2")})
            assertEquals(stream,MediaWaveCapture.Route.WAIT,inspect(HEADER.replace(STREAM,stream)+ROW,10352,3673));
    }
    @Test public void outputFlagsBelongOnlyToTheirOwnThreadAndAreCheckedAfterTheWholeBlock(){
        String missing=HEADER.replace(STREAM,"");
        assertEquals(MediaWaveCapture.Route.WAIT,inspect(HEADER+missing+ROW,10352,3673));
        assertTrue(safe(HEADER.replace("flags 0x8","flags 0x1")+HEADER+ROW,10352,3673));
        assertEquals(MediaWaveCapture.Route.WAIT,inspect(HEADER+ROW+STREAM.replace("flags 0x8","flags 0x2"),10352,3673));
        assertTrue(safe(missing+ROW+STREAM,10352,3673));
    }
    @Test public void knownHarmlessControlBlockFlagsDoNotChangeAnOrdinaryMixerRoute(){
        for(String flags:new String[]{"0x000","0x001","0x002","0x003","0x020","0x023"})
            assertTrue(flags,safe(HEADER+ROW.replace("0x000",flags),10352,3673));
    }
    @Test public void invalidDisabledEndingInterruptedOrUnknownControlBlockFlagsWaitForAReadyTrack(){
        for(String flags:new String[]{"0x004","0x008","0x040","0x080","0x100","0x200","0x400","0x010","0x800","0xdeadbeef"})
            assertEquals(flags,MediaWaveCapture.Route.WAIT,inspect(HEADER+ROW.replace("0x000",flags),10352,3673));
    }
    @Test public void fastTrackPrefixIsRejectedEvenBesideAnOrdinaryTrackInTheSameSession(){
        String fast=" F1 "+ROW.trim()+"\n";
        for(String rows:new String[]{fast,ROW+fast,fast+ROW," F12 S "+ROW.trim()+"\n"})
            assertEquals(rows,MediaWaveCapture.Route.UNSAFE,inspect(HEADER+rows,10352,3673));
        assertTrue(safe(HEADER+fast.replace("10352","10351")+ROW,10352,3673));
        assertTrue(safe(HEADER+fast.replace("yes","no")+ROW,10352,3673));
    }
    @Test public void specialOrUnrecognizedTrackTypesAreNeverTreatedAsOrdinaryDynamicTracks(){
        for(String type:new String[]{"S","P","?","Vendor"})
            assertEquals(type,MediaWaveCapture.Route.WAIT,inspect(HEADER+" "+type+" "+ROW,10352,3673));
    }
    @Test public void malformedSelectedActiveTrackCannotBeIgnoredBesideAValidTrack(){
        String invalid=ROW.replace("1311 A 0x000 00000001","1311 A unknown 00000001");
        assertEquals(MediaWaveCapture.Route.WAIT,inspect(HEADER+invalid+ROW,10352,3673));
        assertEquals(MediaWaveCapture.Route.WAIT,inspect(HEADER+ROW+invalid,10352,3673));
    }
}
