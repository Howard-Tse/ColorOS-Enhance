package dev.howard.colorosenhance.controlcenter;

import android.view.View;

/** Composes native and module displacement, including native writes later in the frame. */
final class ControlCenterTranslation {
    final View view;
    private float baseline,last,delta;
    private boolean owned;
    ControlCenterTranslation(View view){this.view=view;}
    void apply(float delta){
        stage(delta);sync();
    }
    void stage(float delta){this.delta=delta;}
    void sync(){
        if(!owned && delta==0)return;
        float current=view.getTranslationY();if(!owned || Float.compare(current,last)!=0)baseline=current;
        last=baseline+delta;owned=true;if(Float.compare(current,last)!=0)view.setTranslationY(last);
    }
    void restore(){if(owned && Float.compare(view.getTranslationY(),last)==0)view.setTranslationY(baseline);owned=false;delta=0;}
}
