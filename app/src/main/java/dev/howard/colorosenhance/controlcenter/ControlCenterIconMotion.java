package dev.howard.colorosenhance.controlcenter;

import android.graphics.Matrix;
import android.graphics.Rect;
import android.view.View;
import android.view.ViewTreeObserver;

/** Adds the content-scroll displacement to the native real/mirror icon handoff. */
final class ControlCenterIconMotion implements AutoCloseable {
    private final View root,header;
    private final MatrixLease real,mirror;
    private final AlphaLease mirrorVisibility;
    private final Rect visible=new Rect();
    private final ViewTreeObserver.OnScrollChangedListener scrolled=this::schedule;
    private final ViewTreeObserver.OnPreDrawListener draw=()->{queued=false;removeDraw();sync();return true;};
    private float fraction=1;
    private boolean departing,offscreen,queued,closed;

    ControlCenterIconMotion(View root,View header,View mirror,float fraction){
        this.root=root;this.header=header;real=new MatrixLease(header);this.mirror=new MatrixLease(mirror);mirrorVisibility=new AlphaLease(mirror);
        this.fraction=clamp(fraction);
        root.getViewTreeObserver().addOnScrollChangedListener(scrolled);schedule();
    }
    void fraction(float value){if(!Float.isFinite(value))return;fraction=clamp(value);schedule();}
    void schedule(){
        if(closed)return;
        // A hidden panel may not get another pre-draw until it opens again.
        // Release suppression now, so the next ordinary opening is native.
        if(root.getVisibility()!=View.VISIBLE){removeDraw();queued=false;reset();return;}
        // Only the handoff from a scrolled panel needs an extra pre-draw pass.
        if(!departing && (fraction>=1 || root.getScrollY()==0))return;
        ViewTreeObserver observer=root.getViewTreeObserver();
        if(!queued && observer.isAlive()){
            queued=true;observer.addOnPreDrawListener(draw);
        }
    }
    private void removeDraw(){if(root.getViewTreeObserver().isAlive())root.getViewTreeObserver().removeOnPreDrawListener(draw);}
    void sync(){
        if(closed)return;
        int scroll=root.getScrollY();
        if(root.getVisibility()!=View.VISIBLE){reset();return;}
        if(fraction>=1){reset();return;}
        if(scroll==0 && !departing){reset();return;}
        if(!departing){
            // Sample once at departure, before adding any displacement. Keep
            // this choice through quick reversals so the path cannot jump.
            offscreen=!header.getGlobalVisibleRect(visible) || visible.isEmpty();
            departing=true;
        }
        if(offscreen){
            // No seamless stand-in for an icon that has already left the
            // viewport. Let the ordinary status bar follow its native fade,
            // even while an interrupted content rebound still has displacement.
            real.restore();mirror.restore();mirrorVisibility.hide();
        }else{
            mirrorVisibility.restore();
            real.apply(realOffset(scroll,fraction,false));
            mirror.apply(realOffset(scroll,fraction,false));
        }
    }
    private void reset(){departing=false;real.restore();mirror.restore();mirrorVisibility.restore();}
    static float realOffset(int scroll,float fraction,boolean offscreen){return offscreen?0:scroll*(1-clamp(fraction));}
    private static float clamp(float value){return Float.isFinite(value)?Math.max(0,Math.min(1,value)):1;}
    @Override public void close(){
        closed=true;removeDraw();queued=false;
        if(root.getViewTreeObserver().isAlive())root.getViewTreeObserver().removeOnScrollChangedListener(scrolled);
        reset();
    }

    /** Suppress only the QS mirror; never take ownership of the real status bar. */
    static final class AlphaLease {
        private final View view;
        private float baseline;
        private boolean owned;
        AlphaLease(View view){this.view=view;}
        void hide(){
            float current=view.getAlpha();
            if(!owned || current!=0)baseline=current;
            owned=true;if(current!=0)view.setAlpha(0);
        }
        void restore(){if(owned && view.getAlpha()==0)view.setAlpha(baseline);owned=false;}
    }

    /** Separate from translationY, which the native and regional springs own. */
    static final class MatrixLease {
        private final View view;
        private final Matrix baseline=new Matrix(),last=new Matrix(),output=new Matrix();
        private boolean owned,hadBaseline;
        MatrixLease(View view){this.view=view;}
        void apply(float y){
            if(y==0){restore();return;}
            Matrix current=view.getAnimationMatrix();
            if(!owned || current==null || !current.equals(last)){
                hadBaseline=current!=null;
                if(current==null)baseline.reset();else baseline.set(current);
            }
            output.set(baseline);output.postTranslate(0,y);
            if(current==null || !current.equals(output))view.setAnimationMatrix(output);
            last.set(output);owned=true;
        }
        void restore(){
            if(!owned)return;
            Matrix current=view.getAnimationMatrix();
            if(current!=null && current.equals(last))view.setAnimationMatrix(hadBaseline?baseline:null);
            owned=false;
        }
    }
}
