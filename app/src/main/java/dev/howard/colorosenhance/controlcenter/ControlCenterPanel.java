package dev.howard.colorosenhance.controlcenter;

import android.view.View;
import android.view.ViewGroup;

/** Height lease only: native parent, LayoutParams identity and attach lifecycle stay intact. */
final class ControlCenterPanel {
    private final View view;
    private boolean owned;
    private int baseline,applied;
    ControlCenterPanel(View view){this.view=view;}
    boolean matches(int height){return owned && view.getLayoutParams().height==applied && applied==height;}
    void resize(int height){
        ViewGroup.LayoutParams params=view.getLayoutParams();
        if(!owned || params.height!=applied)baseline=params.height;
        applied=height;owned=true;
        if(params.height!=height){params.height=height;view.requestLayout();}
    }
    void restore(){
        if(owned && view.getLayoutParams().height==applied){view.getLayoutParams().height=baseline;view.requestLayout();}
        owned=false;
    }
}
