package dev.howard.colorosenhance.controlcenter;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.widget.SeekBar;
import java.util.function.Consumer;

/** Native hierarchy and direct dragging; notification-center inertia and boundary handoff. */
final class ControlCenterScroller {
    private final ViewGroup root;
    private final ControlCenterPhysics fling;
    private final ControlCenterFrameRate frameRate;
    private final ControlCenterStretch stretch;
    private final ControlCenterBoundaryFeedback boundaryFeedback;
    private final int slop,minVelocity,maxVelocity;
    private final Runnable frame=this::advance;
    private VelocityTracker velocity;
    private ValueAnimator rebound;
    private float downX,downY,lastY,position;
    private int range,motionEpoch;
    private long lastMovement;
    private boolean bottomOnDown,bottomEdge,slider,dragging,edgeSent,catchingMotion,captured;
    ControlCenterScroller(ViewGroup root){this(root,ignored->{});}
    ControlCenterScroller(ViewGroup root,Consumer<String> report){this(root,report,()->false);}
    ControlCenterScroller(ViewGroup root,Consumer<String> report,java.util.function.BooleanSupplier haptics){
        this.root=root;fling=new ControlCenterPhysics(root,report);frameRate=new ControlCenterFrameRate(root);stretch=new ControlCenterStretch(root,fling.notificationExtension,report);
        var limits=ViewConfiguration.get(root.getContext());slop=limits.getScaledTouchSlop();
        minVelocity=limits.getScaledMinimumFlingVelocity();maxVelocity=limits.getScaledMaximumFlingVelocity();
        boundaryFeedback=new ControlCenterBoundaryFeedback(haptics,()->{
            // Same View haptic as COUIRecyclerView/COUIScrollView at their limits.
            // No custom vibration waveform or ignore-system-setting flags.
            if(!root.performHapticFeedback(307))root.performHapticFeedback(20); // Android SCROLL_LIMIT fallback.
        },slop);
    }
    void resize(int range){int next=Math.max(0,range);if(this.range!=next){this.range=next;cancelMotion();stretch.clear();boundaryFeedback.clear();position=Math.max(0,Math.min(next,root.getScrollY()));render(position);}}
    void begin(MotionEvent event){
        boolean moving=rebound!=null || !fling.finished() || outside() || stretch.hasOffsets();
        cancelMotion();recycleVelocity();dragging=false;captured=false;
        downX=event.getX();downY=lastY=event.getY();bottomOnDown=range<=0 || root.getScrollY()>=range-1;
        stretch.prepare();position=stretch.available()?stretch.position(range):ControlCenterPhysics.unmapEdge(root.getScrollY(),range,fling.edgeFactor);
        catchingMotion=moving || outside();
        boundaryFeedback.begin(position,range);
        stretch.begin(Math.max(0,Math.min(range,position)),range);lastMovement=event.getEventTimeNanos();
        var insets=root.getRootWindowInsets();int bottom=insets==null?0:insets.getInsets(android.view.WindowInsets.Type.systemGestures()).bottom;
        bottomEdge=downY>=root.getHeight()-Math.max(bottom,22*root.getResources().getDisplayMetrics().density);
        slider=protectedControl(root,downX,downY);velocity=VelocityTracker.obtain();velocity.addMovement(event);
    }
    boolean intercept(MotionEvent event){
        if(dragging)return true;
        // At the bottom, wait for direction even during a return: a fresh
        // upward gesture closes the panel, a downward one resumes scrolling.
        if(event.getActionMasked()==MotionEvent.ACTION_DOWN && catchingMotion && !bottomOnDown && !slider && !bottomEdge){dragging=captured=true;frameRate.acquire();return true;}
        float threshold=catchingMotion?Math.min(slop,2*root.getResources().getDisplayMetrics().density):slop;
        if(velocity==null || event.getActionMasked()!=MotionEvent.ACTION_MOVE || Math.abs(event.getY()-downY)<=threshold
                || slider || !ControlCenterLayout.keepVerticalGesture(event.getX()-downX,event.getY()-downY,bottomEdge,bottomOnDown))return false;
        dragging=captured=true;lastY=catchingMotion?downY:downY-Math.copySign(slop,downY-event.getY());frameRate.acquire();return true;
    }
    boolean dragging(){return dragging;}
    boolean captured(){return captured;}
    boolean touch(MotionEvent event){
        if(!dragging)return false;if(velocity!=null)velocity.addMovement(event);
        switch(event.getActionMasked()){
            case MotionEvent.ACTION_MOVE -> {float y=event.getY();render(position+lastY-y,true);lastY=y;}
            case MotionEvent.ACTION_POINTER_UP -> {int index=event.getActionIndex()==0?1:0;if(index<event.getPointerCount()){lastY=event.getY(index);if(velocity!=null)velocity.clear();}}
            case MotionEvent.ACTION_UP -> {
                if(outside())startRebound();
                else if(velocity!=null){velocity.computeCurrentVelocity(1000,maxVelocity);int speed=Math.round(-velocity.getYVelocity());
                    if(Math.abs(speed)>=minVelocity){edgeSent=false;fling.fling(Math.round(position),speed,range,maxOver());root.postOnAnimation(frame);}else settle();}
                else settle();dragging=false;recycleVelocity();
            }
            case MotionEvent.ACTION_CANCEL -> {dragging=false;recycleVelocity();settle();}
        }
        return true;
    }
    void end(MotionEvent event,boolean interactive){
        if(event.getActionMasked()!=MotionEvent.ACTION_UP && event.getActionMasked()!=MotionEvent.ACTION_CANCEL)return;
        dragging=false;recycleVelocity();
        // Slider/native-edge gestures are intentionally not captured. If they
        // interrupted a return, resume it when the panel remains fully open.
        if(!captured && interactive && (outside() || stretch.hasOffsets())){frameRate.acquire();startRebound();}
    }
    private int maxOver(){return Math.max(1,Math.round(root.getHeight()*1.5f));}
    private boolean outside(){return position<0 || position>range;}
    private void render(float raw){render(raw,false);}
    private void render(float raw,boolean userMotion){
        float previousPosition=position;
        float previous=ControlCenterPhysics.mapEdge(position,range,fling.edgeFactor);
        position=Math.max(-maxOver(),Math.min(range+maxOver(),raw));
        if(userMotion)boundaryFeedback.motion(previousPosition,position,range);
        int next=Math.round(ControlCenterPhysics.mapEdge(position,range,fling.edgeFactor));long now=System.nanoTime();
        if(lastMovement>0 && now>lastMovement)frameRate.velocity(Math.abs(next-previous)*1_000_000_000f/(now-lastMovement));
        lastMovement=now;
        if(stretch.available()){float base=Math.max(0,Math.min(range,position));root.scrollTo(0,Math.round(base));stretch.pose(base-position,base);}
        else root.scrollTo(0,next);
    }
    private void advance(){
        try{
            if(fling.advance()){
                int y=fling.y();render(y,true);
                if(!edgeSent && outside()){edgeSent=true;fling.edge(y,Math.max(0,Math.min(range,y)),range,maxOver());}
                if(!fling.finished())root.postOnAnimation(frame);else finishInertia();
            }else finishInertia();
        }catch(RuntimeException|LinkageError unavailable){fling.abort();settle();}
    }
    private void finishInertia(){fling.abort();settle();}
    private void settle(){if(outside() || stretch.hasOffsets())startRebound();else frameRate.release();}
    private void startRebound(){
        fling.abort();root.removeCallbacks(frame);int epoch=++motionEpoch;
        float edge=Math.max(0,Math.min(range,position));
        if(stretch.release(()->{if(epoch==motionEpoch){position=edge;root.scrollTo(0,Math.round(edge));frameRate.release();}}))return;
        ValueAnimator animator=ValueAnimator.ofFloat(position,edge);rebound=animator;
        animator.setDuration(fling.reboundDuration);animator.setInterpolator(fling.rebound);
        animator.addUpdateListener(value->{if(epoch==motionEpoch)render((float)value.getAnimatedValue());});
        animator.addListener(new AnimatorListenerAdapter(){@Override public void onAnimationEnd(Animator animation){
            if(epoch==motionEpoch){rebound=null;render(edge);frameRate.release();}
        }});animator.start();
    }
    private void cancelMotion(){
        ++motionEpoch;root.removeCallbacks(frame);fling.abort();
        if(rebound!=null){ValueAnimator previous=rebound;rebound=null;previous.cancel();}
        stretch.freeze();
        frameRate.release();
    }
    private void recycleVelocity(){if(velocity!=null){velocity.recycle();velocity=null;}}
    void reset(){cancelMotion();stretch.clear();recycleVelocity();boundaryFeedback.clear();dragging=false;position=0;root.scrollTo(0,0);}
    void close(){reset();stretch.dispose();}
    private static boolean protectedControl(View view,float x,float y){
        if(view.getVisibility()!=View.VISIBLE)return false;
        if(view instanceof SeekBar || view.getClass().getSimpleName().endsWith("ToggleSliderLayout"))return true;
        if(view instanceof ViewGroup group)for(int i=group.getChildCount()-1;i>=0;i--){
            View child=group.getChildAt(i);float cx=x+group.getScrollX()-child.getLeft()-child.getTranslationX();
            float cy=y+group.getScrollY()-child.getTop()-child.getTranslationY();
            if(cx>=0 && cy>=0 && cx<child.getWidth() && cy<child.getHeight() && protectedControl(child,cx,cy))return true;
        }
        return false;
    }
}
