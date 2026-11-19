package dev.howard.colorosenhance.controlcenter;

import android.app.Activity;
import android.app.Application;
import android.graphics.Matrix;
import android.view.View;
import android.widget.FrameLayout;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class) @Config(sdk=36,application=Application.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class ControlCenterIconMotionTest {
    private final android.content.Context context=org.robolectric.RuntimeEnvironment.getApplication();
    private static float matrixY(View view){Matrix matrix=view.getAnimationMatrix();if(matrix==null)return 0;float[] p={0,0};matrix.mapPoints(p);return p[1];}
    @Test public void visibleIconsKeepTheScrolledOriginAndFinishAtTheNativeStatusBar(){
        for(float f:new float[]{1,.9f,.6f,.2f,0,.2f,.6f,.9f,1}){
            float delta=ControlCenterIconMotion.realOffset(120,f,false);
            assertEquals(-120*f,-120+delta,.001f);
        }
    }
    @Test public void offscreenIconsDoNotMoveTheRealHeader(){
        for(float f:new float[]{.99f,.6f,.2f,0,.2f,.6f,.99f}){
            assertEquals(0,ControlCenterIconMotion.realOffset(800,f,true),0);
        }
    }
    @Test public void noScrollLeavesTheNativePathExactlyUnchanged(){
        for(float f:new float[]{0,.2f,.8f,1})for(boolean offscreen:new boolean[]{true,false}){
            assertEquals(0,ControlCenterIconMotion.realOffset(0,f,offscreen),0);
        }
    }
    @Test public void matrixDisplacementPreservesNativeTransformsAndDoesNotAccumulate(){
        View view=new View(context);Matrix nativeMatrix=new Matrix();nativeMatrix.setScale(.8f,1.2f);nativeMatrix.postTranslate(17,9);view.setAnimationMatrix(nativeMatrix);
        view.setTranslationY(31);view.setTranslationX(12);view.setAlpha(.35f);
        var lease=new ControlCenterIconMotion.MatrixLease(view);
        lease.apply(50);lease.apply(60);lease.apply(60);
        float[] p={10,10};view.getAnimationMatrix().mapPoints(p);assertArrayEquals(new float[]{25,81},p,.001f);
        assertEquals(31,view.getTranslationY(),0);assertEquals(12,view.getTranslationX(),0);assertEquals(.35f,view.getAlpha(),0);
        lease.restore();assertEquals(nativeMatrix,view.getAnimationMatrix());
    }
    @Test public void foreignMatrixWritesBecomeTheNewBaselineAndSurviveCleanup(){
        View view=new View(context);var lease=new ControlCenterIconMotion.MatrixLease(view);lease.apply(10);
        Matrix foreign=new Matrix();foreign.setTranslate(8,23);view.setAnimationMatrix(foreign);
        lease.restore();assertEquals(foreign,view.getAnimationMatrix());
        lease.apply(10);assertEquals(33,matrixY(view),0);lease.restore();assertEquals(foreign,view.getAnimationMatrix());
        lease.apply(5);view.setAnimationMatrix(null);lease.apply(7);assertEquals(7,matrixY(view),0);lease.restore();assertNull(view.getAnimationMatrix());
    }
    @Test public void partiallyVisibleHeaderKeepsItsPathThroughReversalsAndOffscreenHeaderUsesTheMirror(){
        var activity=Robolectric.buildActivity(Activity.class).setup();
        try{
            var root=new FrameLayout(activity.get());var header=new View(activity.get());var mirror=new View(activity.get());
            var params=new FrameLayout.LayoutParams(100,50);params.topMargin=100;root.addView(header,params);root.addView(mirror,new FrameLayout.LayoutParams(100,50));
            activity.get().setContentView(root);root.measure(View.MeasureSpec.makeMeasureSpec(300,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(500,View.MeasureSpec.EXACTLY));root.layout(0,0,300,500);
            root.scrollTo(0,120);var motion=new ControlCenterIconMotion(root,header,mirror,1);
            motion.fraction(.8f);motion.sync();assertEquals(24,matrixY(header),.001f);assertEquals(24,matrixY(mirror),.001f);
            // The native panel moves out during collapse; do not change modes mid-flight.
            header.setTranslationY(-500);motion.fraction(.5f);motion.sync();assertEquals(60,matrixY(header),.001f);
            motion.fraction(.9f);motion.sync();assertEquals(12,matrixY(header),.001f);
            header.setTranslationY(0);motion.fraction(1);motion.sync();assertNull(header.getAnimationMatrix());
            root.scrollTo(0,300);motion.fraction(.8f);motion.sync();assertNull(header.getAnimationMatrix());assertNull(mirror.getAnimationMatrix());assertEquals(0,mirror.getAlpha(),0);
            motion.fraction(.2f);motion.sync();assertNull(mirror.getAnimationMatrix());assertEquals(0,mirror.getAlpha(),0);
            root.setVisibility(View.INVISIBLE);motion.sync();assertNull(mirror.getAnimationMatrix());assertEquals(1,mirror.getAlpha(),0);motion.close();
        }finally{activity.pause().stop().destroy();}
    }
    @Test public void closingCancelsThePendingDrawAndRestoresNativeState(){
        var root=new FrameLayout(context);var header=new View(context);var mirror=new View(context);root.addView(header);root.addView(mirror);
        root.scrollTo(0,300);var motion=new ControlCenterIconMotion(root,header,mirror,.5f);motion.sync();assertEquals(0,mirror.getAlpha(),0);
        motion.close();root.getViewTreeObserver().dispatchOnPreDraw();assertNull(header.getAnimationMatrix());assertNull(mirror.getAnimationMatrix());assertEquals(1,mirror.getAlpha(),0);
    }
    @Test public void offscreenMirrorStaysSuppressedThroughInterruptedReboundAndEarlyScrollReset(){
        var root=new FrameLayout(context);var header=new View(context);var mirror=new View(context);root.addView(header);root.addView(mirror);
        root.scrollTo(0,800);var motion=new ControlCenterIconMotion(root,header,mirror,1);
        for(float f:new float[]{.9f,.6f,.8f,.4f,.1f,0}){
            root.setTranslationY(-200*(1-f));header.setTranslationY(-90*f);mirror.setTranslationY(70*f);
            motion.fraction(f);motion.sync();assertEquals(0,mirror.getAlpha(),0);assertNull(mirror.getAnimationMatrix());assertNull(header.getAnimationMatrix());
        }
        root.scrollTo(0,0);motion.sync();assertEquals(0,mirror.getAlpha(),0);
        motion.fraction(1);motion.sync();assertEquals(1,mirror.getAlpha(),0);motion.close();
    }
    @Test public void mirrorSuppressionPreservesNativeAlphaChangesAndTheOrdinaryStatusBar(){
        var mirror=new View(context);var statusBar=new View(context);statusBar.setAlpha(.4f);mirror.setAlpha(.7f);
        var lease=new ControlCenterIconMotion.AlphaLease(mirror);lease.hide();lease.hide();assertEquals(.4f,statusBar.getAlpha(),0);
        lease.restore();assertEquals(.7f,mirror.getAlpha(),0);
        lease.hide();mirror.setAlpha(.3f);lease.restore();assertEquals(.3f,mirror.getAlpha(),0);
        lease.hide();mirror.setAlpha(.8f);lease.hide();lease.restore();assertEquals(.8f,mirror.getAlpha(),0);
    }
    @Test public void hiddenPanelReleasesSuppressionWithoutWaitingForAnotherDraw(){
        var root=new FrameLayout(context);var header=new View(context);var mirror=new View(context);root.addView(header);root.addView(mirror);
        root.scrollTo(0,800);var motion=new ControlCenterIconMotion(root,header,mirror,.4f);motion.sync();assertEquals(0,mirror.getAlpha(),0);
        root.setVisibility(View.INVISIBLE);motion.schedule();assertEquals(1,mirror.getAlpha(),0);
        root.scrollTo(0,0);root.setVisibility(View.VISIBLE);motion.fraction(.2f);motion.sync();assertEquals(1,mirror.getAlpha(),0);assertNull(mirror.getAnimationMatrix());motion.close();
    }
    @Test public void preDrawStillTransfersAndRestoresOffscreenIconsAfterSkippingOrdinaryFrames(){
        var root=new FrameLayout(context);var header=new View(context);var mirror=new View(context);root.addView(header);root.addView(mirror);
        var motion=new ControlCenterIconMotion(root,header,mirror,1);
        try {
            motion.fraction(.8f);root.getViewTreeObserver().dispatchOnPreDraw();assertEquals(1,mirror.getAlpha(),0);
            root.scrollTo(0,800);motion.fraction(.6f);root.getViewTreeObserver().dispatchOnPreDraw();assertEquals(0,mirror.getAlpha(),0);
            root.scrollTo(0,0);motion.fraction(1);root.getViewTreeObserver().dispatchOnPreDraw();assertEquals(1,mirror.getAlpha(),0);
        }finally{motion.close();}
    }
}
