package dev.howard.colorosenhance.controlcenter;

import org.junit.Test;
import static org.junit.Assert.*;

public class ControlCenterBoundaryFeedbackTest {
    @Test public void heldEdgeOnlyPulsesOnceAndNeedsMeaningfulTravelToRearm(){
        int[] pulses={0};var feedback=new ControlCenterBoundaryFeedback(()->true,()->pulses[0]++,8);
        feedback.begin(0,100);feedback.motion(0,-10,100);feedback.motion(-10,-40,100);
        feedback.motion(-40,0,100);feedback.motion(0,3,100);feedback.motion(3,-1,100);
        assertEquals(1,pulses[0]);
        feedback.motion(-1,10,100);feedback.motion(10,0,100);assertEquals(2,pulses[0]);
    }
    @Test public void alternatingEdgesInOneGestureRemainIndependent(){
        int[] pulses={0};var feedback=new ControlCenterBoundaryFeedback(()->true,()->pulses[0]++,8);
        feedback.begin(50,100);
        for(int i=0;i<5;i++){
            feedback.motion(50,-10,100);feedback.motion(-10,50,100);
            feedback.motion(50,110,100);feedback.motion(110,50,100);
        }
        assertEquals(10,pulses[0]);
    }
    @Test public void grabbingAnExistingReturnDoesNotPulseAgainUntilLeavingTheEdge(){
        int[] pulses={0};var feedback=new ControlCenterBoundaryFeedback(()->true,()->pulses[0]++,8);
        feedback.begin(-20,100);feedback.motion(-20,-40,100);assertEquals(0,pulses[0]);
        feedback.motion(-40,20,100);feedback.motion(20,-2,100);assertEquals(1,pulses[0]);
    }
    @Test public void liveDisableAndEmptyContentNeverVibrate(){
        boolean[] enabled={false};int[] pulses={0};
        var feedback=new ControlCenterBoundaryFeedback(()->enabled[0],()->pulses[0]++,8);
        feedback.begin(0,100);feedback.motion(0,-30,100);assertEquals(0,pulses[0]);
        enabled[0]=true;feedback.begin(0,100);feedback.motion(0,-30,100);assertEquals(1,pulses[0]);
        enabled[0]=false;feedback.motion(-30,50,100);feedback.motion(50,110,100);assertEquals(1,pulses[0]);
        enabled[0]=true;feedback.begin(0,0);feedback.motion(0,-30,0);assertEquals(1,pulses[0]);
    }
    @Test public void hapticFailureIsIsolatedAndDoesNotKeepRetryingAtTheEdge(){
        int[] attempts={0};var feedback=new ControlCenterBoundaryFeedback(()->true,()->{attempts[0]++;throw new IllegalStateException();},8);
        feedback.begin(50,100);feedback.motion(50,100,100);feedback.motion(100,130,100);
        assertEquals(1,attempts[0]);
    }
}
