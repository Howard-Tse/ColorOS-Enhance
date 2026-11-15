package dev.howard.colorosenhance.controlcenter;

import android.view.View;

/** A view-local scrolling hint, restored on stop; never changes display/user settings. */
final class ControlCenterFrameRate {
    private final View view;
    private boolean owned;
    private float previousRate,previousVelocity,rate,velocity;
    ControlCenterFrameRate(View view){this.view=view;}
    void acquire(){
        if(owned)return;
        previousRate=view.getRequestedFrameRate();previousVelocity=view.getFrameContentVelocity();
        rate=previousRate;
        var display=view.getDisplay();
        if(display!=null){
            var current=display.getMode();float maximum=current.getRefreshRate();
            for(var mode:display.getSupportedModes())if(mode.getPhysicalWidth()==current.getPhysicalWidth() && mode.getPhysicalHeight()==current.getPhysicalHeight())
                maximum=Math.max(maximum,mode.getRefreshRate());
            rate=maximum;
        }
        owned=true;view.setRequestedFrameRate(rate);
    }
    void velocity(float pixelsPerSecond){
        if(!owned)return;
        velocity=Math.max(0,Math.min(30000,pixelsPerSecond));view.setFrameContentVelocity(velocity);
    }
    void release(){
        if(!owned)return;
        if(Float.compare(view.getRequestedFrameRate(),rate)==0)view.setRequestedFrameRate(previousRate);
        if(Float.compare(view.getFrameContentVelocity(),velocity)==0)view.setFrameContentVelocity(previousVelocity);
        owned=false;
    }
}
