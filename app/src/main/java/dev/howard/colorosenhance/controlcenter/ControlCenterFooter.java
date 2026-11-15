package dev.howard.colorosenhance.controlcenter;

import android.view.View;
import android.view.ViewGroup;

/** Move the native footer past the extra rows without overwriting later native changes. */
final class ControlCenterFooter {
    private final View view;
    private boolean owned;
    private int baseline,applied;
    ControlCenterFooter(View view){this.view=view;}
    boolean matches(int delta){return owned && view.getLayoutParams() instanceof ViewGroup.MarginLayoutParams params
            && params.topMargin==applied && applied==baseline+delta;}
    void resize(int delta){
        if(!(view.getLayoutParams() instanceof ViewGroup.MarginLayoutParams params))
            throw new IllegalStateException("Unsupported QS footer constraints");
        if(!owned || params.topMargin!=applied)baseline=params.topMargin;
        applied=baseline+delta;owned=true;
        if(params.topMargin!=applied){params.topMargin=applied;view.requestLayout();}
    }
    void restore(){
        if(owned && view.getLayoutParams() instanceof ViewGroup.MarginLayoutParams params && params.topMargin==applied){
            params.topMargin=baseline;view.requestLayout();
        }
        owned=false;
    }
}
