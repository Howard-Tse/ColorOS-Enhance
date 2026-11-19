package dev.howard.colorosenhance.controlcenter;

import java.util.function.BooleanSupplier;

/** One feedback per reached edge; passive spring frames never enter this detector. */
final class ControlCenterBoundaryFeedback {
    private final BooleanSupplier enabled;
    private final Runnable feedback;
    private final float rearmDistance;
    private boolean topReady,bottomReady;

    ControlCenterBoundaryFeedback(BooleanSupplier enabled,Runnable feedback,float rearmDistance) {
        this.enabled=enabled;this.feedback=feedback;this.rearmDistance=Math.max(1,rearmDistance);
    }
    void begin(float position,int range) {
        boolean active=range>0 && enabled.getAsBoolean();
        topReady=active && position>=0;
        bottomReady=active && position<=range;
    }
    void motion(float previous,float position,int range) {
        if(range<=0 || !enabled.getAsBoolean()){clear();return;}
        if(position>rearmDistance)topReady=true;
        if(position<range-rearmDistance)bottomReady=true;
        if(topReady && position<=0 && (previous>0 || position<0)){topReady=false;emit();}
        else if(bottomReady && position>=range && (previous<range || position>range)){bottomReady=false;emit();}
    }
    private void emit() {
        try { feedback.run(); }
        catch(RuntimeException|LinkageError unavailable){/* A missing haptic must never interrupt scrolling. */}
    }
    void clear(){topReady=bottomReady=false;}
}
