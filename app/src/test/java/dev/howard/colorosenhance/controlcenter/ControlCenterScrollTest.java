package dev.howard.colorosenhance.controlcenter;

import android.app.Activity;
import android.app.Application;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.SeekBar;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class) @Config(sdk=36,application=Application.class)
public class ControlCenterScrollTest {
    @Test public void expansionNeverDetachesNativeViewsOrReplacesTheirLayoutParams(){
        var activity=Robolectric.buildActivity(Activity.class).setup().visible();
        try {
            var owner=activity.get();var root=new FrameLayout(owner);var content=new FrameLayout(owner);
            var original=new FrameLayout.LayoutParams(-1,-1);original.setMarginEnd(19);root.addView(content,original);owner.setContentView(root);
            int[] attaches={0,0};content.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener(){
                public void onViewAttachedToWindow(View view){attaches[0]++;}public void onViewDetachedFromWindow(View view){attaches[1]++;}});
            var lease=new ControlCenterPanel(content);lease.resize(1800);lease.resize(2400);
            root.measure(exact(1000),exact(600));root.layout(0,0,1000,600);
            assertSame(root,content.getParent());assertSame(original,content.getLayoutParams());assertEquals(1,root.getChildCount());
            assertEquals(2400,content.getHeight());assertArrayEquals(new int[]{0,0},attaches);
            original.width=900;original.setMarginEnd(37);lease.restore();
            assertSame(original,content.getLayoutParams());assertEquals(-1,original.height);assertEquals(900,original.width);assertEquals(37,original.getMarginEnd());
            assertArrayEquals(new int[]{0,0},attaches);
        }finally{activity.pause().stop().destroy();}
    }
    @Test public void nativeHeightUpdatesArePreservedOnCleanup(){
        var activity=Robolectric.buildActivity(Activity.class).setup();
        try {
            var content=new FrameLayout(activity.get());var params=new FrameLayout.LayoutParams(-1,-1);content.setLayoutParams(params);
            var lease=new ControlCenterPanel(content);lease.resize(1800);params.height=800;lease.restore();assertEquals(800,params.height);
            lease.resize(2400);lease.restore();assertEquals(800,params.height);
        }finally{activity.pause().stop().destroy();}
    }
    @Test public void footerMovesPastExtraRowsAndRestoresTheLatestNativeMargin(){
        var owner=Robolectric.buildActivity(Activity.class).setup();
        try {
            var view=new View(owner.get());var params=new FrameLayout.LayoutParams(100,100);params.topMargin=400;view.setLayoutParams(params);
            var footer=new ControlCenterFooter(view);footer.resize(300);assertEquals(700,params.topMargin);assertTrue(footer.matches(300));
            footer.resize(300);assertEquals(700,params.topMargin);footer.resize(500);assertEquals(900,params.topMargin);
            params.topMargin=250;assertFalse(footer.matches(500));footer.resize(500);assertEquals(750,params.topMargin);
            footer.restore();assertEquals(250,params.topMargin);footer.restore();assertEquals(250,params.topMargin);
            footer.resize(200);params.topMargin=123;footer.restore();assertEquals(123,params.topMargin);
        }finally{owner.pause().stop().destroy();}
    }
    @Test public void bodyScrollsButSlidersBottomStripAndBottomUpwardGesturesStayNative(){
        var activity=Robolectric.buildActivity(Activity.class).setup().visible();
        try {
            var owner=activity.get();var root=new FrameLayout(owner);var content=new FrameLayout(owner);
            var slider=new SeekBar(owner);content.addView(slider,new FrameLayout.LayoutParams(300,200));root.addView(content,new FrameLayout.LayoutParams(1000,1800));
            owner.setContentView(root);root.measure(exact(1000),exact(600));root.layout(0,0,1000,600);
            var scroll=new ControlCenterScroller(root);scroll.resize(1200);float originalRate=root.getRequestedFrameRate();
            begin(scroll,100,150);assertFalse(intercept(scroll,100,50));
            begin(scroll,800,400);assertFalse(intercept(scroll,800,399));assertTrue(intercept(scroll,800,250));
            touch(scroll,800,250,MotionEvent.ACTION_MOVE);int first=root.getScrollY();assertTrue(first>0);assertTrue(first<150);
            touch(scroll,800,150,MotionEvent.ACTION_MOVE);assertEquals(first+100,root.getScrollY());
            touch(scroll,800,150,MotionEvent.ACTION_CANCEL);assertFalse(scroll.dragging());assertEquals(originalRate,root.getRequestedFrameRate(),.01f);
            begin(scroll,800,599);assertFalse(intercept(scroll,800,400));
            root.scrollTo(0,1200);begin(scroll,800,300);assertFalse(intercept(scroll,800,150));assertTrue(intercept(scroll,800,450));
            scroll.reset();assertEquals(0,root.getScrollY());assertFalse(scroll.dragging());
        }finally{activity.pause().stop().destroy();}
    }
    @Test public void notificationLeadingGroupDampingIsContinuousAndInvertibleForInterruptedDrags(){
        assertEquals(0,ControlCenterPhysics.mapEdge(0,1200,.24f),.001f);
        assertEquals(1200,ControlCenterPhysics.mapEdge(1200,1200,.24f),.001f);
        for(float raw:new float[]{-100,-10,200,1190,1210,1300}){
            float shown=ControlCenterPhysics.mapEdge(raw,1200,.24f);assertTrue(shown>=-24 && shown<=1224);
            assertEquals(raw,ControlCenterPhysics.unmapEdge(shown,1200,.24f),.01f);
        }
    }
    @Test public void scrollingHintsAreReleasedWithoutOverridingOtherNativeUpdates(){
        var owner=Robolectric.buildActivity(Activity.class).setup().visible();
        try {
            var root=new FrameLayout(owner.get());owner.get().setContentView(root);
            root.setRequestedFrameRate(60);root.setFrameContentVelocity(0);
            var hint=new ControlCenterFrameRate(root);hint.acquire();hint.velocity(1200);hint.release();
            assertEquals(60,root.getRequestedFrameRate(),.01);assertEquals(0,root.getFrameContentVelocity(),.01);
            hint.acquire();hint.velocity(1000);root.setRequestedFrameRate(90);hint.release();assertEquals(90,root.getRequestedFrameRate(),.01);
        }finally{owner.pause().stop().destroy();}
    }
    @Test public void grabbingBoundaryReturnBeforeItsNextFrameKeepsItsCurrentPosition(){
        var owner=Robolectric.buildActivity(Activity.class).setup().visible();
        try {
            var root=new FrameLayout(owner.get());root.addView(new FrameLayout(owner.get()),new FrameLayout.LayoutParams(1000,1800));
            owner.get().setContentView(root);root.measure(exact(1000),exact(600));root.layout(0,0,1000,600);
            var scroll=new ControlCenterScroller(root);scroll.resize(1200);begin(scroll,800,300);assertTrue(intercept(scroll,800,520));
            touch(scroll,800,520,MotionEvent.ACTION_MOVE);assertTrue(root.getScrollY()<0);touch(scroll,800,520,MotionEvent.ACTION_UP);
            int before=root.getScrollY();assertTrue(before<0);begin(scroll,800,400);assertEquals(before,root.getScrollY());
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(320));
            assertEquals(before,root.getScrollY());scroll.reset();assertEquals(0,root.getScrollY());
        }finally{owner.pause().stop().destroy();}
    }
    @Test public void frozenRegionPhasesRetireByInwardContentTravelInsteadOfBlockingScrolling(){
        assertEquals(1,ControlCenterStretch.carryWeight(0,0,1,200),.001f);
        assertEquals(.5f,ControlCenterStretch.carryWeight(100,0,1,200),.001f);
        assertEquals(0,ControlCenterStretch.carryWeight(300,0,1,200),.001f);
        assertEquals(1,ControlCenterStretch.carryWeight(1200,1200,2,200),.001f);
        assertEquals(.5f,ControlCenterStretch.carryWeight(1100,1200,2,200),.001f);
        assertEquals(0,ControlCenterStretch.carryWeight(900,1200,2,200),.001f);
    }
    @Test public void moduleRegionOffsetsDoNotEraseNativeTranslationChanges(){
        var owner=Robolectric.buildActivity(Activity.class).setup();
        try{
            var view=new View(owner.get());view.setTranslationY(7);var lease=new ControlCenterTranslation(view);
            lease.apply(20);assertEquals(27,view.getTranslationY(),.01f);lease.apply(30);assertEquals(37,view.getTranslationY(),.01f);
            view.setTranslationY(11);lease.apply(30);assertEquals(41,view.getTranslationY(),.01f);lease.restore();assertEquals(11,view.getTranslationY(),.01f);
            lease.apply(20);view.setTranslationY(9);lease.restore();assertEquals(9,view.getTranslationY(),.01f);
        }finally{owner.pause().stop().destroy();}
    }
    @Test public void nativeOpeningWritesAfterTheReturnCallbackAreComposedBeforeTheSameDraw()throws Exception {
        var owner=Robolectric.buildActivity(Activity.class).setup();
        try{
            var root=new FrameLayout(owner.get());var view=new View(owner.get());root.addView(view);view.setScaleY(.96f);
            var lease=new ControlCenterTranslation(view);var stretch=new ControlCenterStretch(root,null,ignored->{});
            NativeControlCenter.put(NativeControlCenter.field(ControlCenterStretch.class,"views"),stretch,java.util.List.of(java.util.List.of(view)));
            NativeControlCenter.put(NativeControlCenter.field(ControlCenterStretch.class,"leases"),stretch,java.util.List.of(java.util.List.of(lease)));
            NativeControlCenter.call(NativeControlCenter.method(ControlCenterStretch.class,"allowRegionOverflow"),stretch);
            for(int frame=0;frame<20;frame++){
                float nativeY=40-frame*2,moduleY=80-frame*3;lease.stage(moduleY);
                // Native Choreographer callback runs after the module callback.
                view.setTranslationY(nativeY);root.getViewTreeObserver().dispatchOnPreDraw();
                assertEquals(nativeY+moduleY,view.getTranslationY(),.001f);
                assertEquals(.96f,view.getScaleY(),.001f);
                root.getViewTreeObserver().dispatchOnPreDraw();assertEquals(nativeY+moduleY,view.getTranslationY(),.001f);
            }
            stretch.clear();view.setTranslationY(13);root.getViewTreeObserver().dispatchOnPreDraw();assertEquals(13,view.getTranslationY(),.001f);
        }finally{owner.pause().stop().destroy();}
    }
    private static final class CountedTranslationView extends View {
        int writes;CountedTranslationView(android.content.Context context){super(context);}
        @Override public void setTranslationY(float value){writes++;super.setTranslationY(value);}
    }
    @Test public void composingAnUnchangedFrameDoesNotWriteOrAccumulateOffsets(){
        var owner=Robolectric.buildActivity(Activity.class).setup();
        try{
            var view=new CountedTranslationView(owner.get());view.setTranslationY(7);var lease=new ControlCenterTranslation(view);
            int before=view.writes;lease.stage(18);lease.stage(20);assertEquals(before,view.writes);
            lease.apply(20);int count=view.writes;
            for(int i=0;i<120;i++){lease.sync();lease.apply(20);}
            assertEquals(count,view.writes);assertEquals(27,view.getTranslationY(),.001f);
            view.setTranslationY(3);lease.sync();assertEquals(23,view.getTranslationY(),.001f);
            lease.restore();assertEquals(3,view.getTranslationY(),.001f);count=view.writes;
            lease.sync();assertEquals(count,view.writes);
        }finally{owner.pause().stop().destroy();}
    }
    public static final class PausingSpring {Runnable notify;public void pause(){notify.run();}}
    @Test public void pausingFreshNativeSpringsCannotZeroTheFirstDraggedRegionSnapshot()throws Exception {
        var owner=Robolectric.buildActivity(Activity.class).setup();
        try {
            var root=new FrameLayout(owner.get());var view=new View(owner.get());root.addView(view);
            var lease=new ControlCenterTranslation(view);lease.apply(25);
            var stretch=new ControlCenterStretch(root,null,ignored->{});var nativeSpring=new PausingSpring();
            var changed=NativeControlCenter.method(ControlCenterStretch.class,"springChanged",int.class,float.class);
            nativeSpring.notify=()->NativeControlCenter.call(changed,stretch,0,0f);
            NativeControlCenter.put(NativeControlCenter.field(ControlCenterStretch.class,"chain"),stretch,nativeSpring);
            NativeControlCenter.put(NativeControlCenter.field(ControlCenterStretch.class,"reset"),stretch,NativeControlCenter.method(PausingSpring.class,"pause"));
            NativeControlCenter.put(NativeControlCenter.field(ControlCenterStretch.class,"views"),stretch,java.util.List.of(java.util.List.of(view)));
            NativeControlCenter.put(NativeControlCenter.field(ControlCenterStretch.class,"leases"),stretch,java.util.List.of(java.util.List.of(lease)));
            NativeControlCenter.put(NativeControlCenter.field(ControlCenterStretch.class,"offsets"),stretch,new float[]{25});
            stretch.freeze();assertEquals(25,view.getTranslationY(),.01f);
            assertArrayEquals(new float[]{25},(float[])NativeControlCenter.get(NativeControlCenter.field(ControlCenterStretch.class,"offsets"),stretch),.01f);
            lease.restore();
        }finally{owner.pause().stop().destroy();}
    }
    @Test public void reversingAnActiveReturnIsCapturedOnDownAndMovesBeforeANewTouchSlop(){
        var owner=Robolectric.buildActivity(Activity.class).setup().visible();
        try {
            var root=new FrameLayout(owner.get());root.addView(new FrameLayout(owner.get()),new FrameLayout.LayoutParams(1000,1800));owner.get().setContentView(root);
            root.measure(exact(1000),exact(600));root.layout(0,0,1000,600);var scroll=new ControlCenterScroller(root);scroll.resize(1200);
            begin(scroll,800,300);assertTrue(intercept(scroll,800,520));touch(scroll,800,520,MotionEvent.ACTION_MOVE);touch(scroll,800,520,MotionEvent.ACTION_UP);
            int before=root.getScrollY();begin(scroll,800,400);
            MotionEvent down=MotionEvent.obtain(0,0,MotionEvent.ACTION_DOWN,800,400,0);
            try{assertTrue(scroll.intercept(down));}finally{down.recycle();}
            touch(scroll,800,395,MotionEvent.ACTION_MOVE);assertTrue(root.getScrollY()>before);scroll.reset();
        }finally{owner.pause().stop().destroy();}
    }
    @Test public void protectedSliderReleaseResumesAnInterruptedBoundaryReturn(){
        var owner=Robolectric.buildActivity(Activity.class).setup().visible();
        try {
            var root=new FrameLayout(owner.get());var content=new FrameLayout(owner.get());content.addView(new SeekBar(owner.get()),new FrameLayout.LayoutParams(300,200));
            root.addView(content,new FrameLayout.LayoutParams(1000,1800));owner.get().setContentView(root);root.measure(exact(1000),exact(600));root.layout(0,0,1000,600);
            var scroll=new ControlCenterScroller(root);scroll.resize(1200);begin(scroll,800,300);assertTrue(intercept(scroll,800,520));
            touch(scroll,800,520,MotionEvent.ACTION_MOVE);touch(scroll,800,520,MotionEvent.ACTION_UP);assertTrue(root.getScrollY()<0);
            begin(scroll,100,100);MotionEvent down=MotionEvent.obtain(0,0,MotionEvent.ACTION_DOWN,100,100,0);
            try{assertFalse(scroll.intercept(down));}finally{down.recycle();}
            MotionEvent up=MotionEvent.obtain(0,100,MotionEvent.ACTION_UP,100,100,0);try{scroll.end(up,true);}finally{up.recycle();}
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(400));
            assertEquals(0,root.getScrollY());scroll.reset();
        }finally{owner.pause().stop().destroy();}
    }
    @Test public void grabbingLayeredReboundScrollsImmediatelyInBothDirectionsWithoutDiscardingItsShape()throws Exception {
        var owner=Robolectric.buildActivity(Activity.class).setup().visible();
        try {
            var root=new FrameLayout(owner.get());var content=new FrameLayout(owner.get());root.addView(content,new FrameLayout.LayoutParams(1000,1800));owner.get().setContentView(root);
            root.measure(exact(1000),exact(600));root.layout(0,0,1000,600);
            for(boolean bottom:new boolean[]{false,true}){
                var scroll=new ControlCenterScroller(root);scroll.resize(1200);root.scrollTo(0,bottom?1200:0);
                var stretch=(ControlCenterStretch)NativeControlCenter.get(NativeControlCenter.field(ControlCenterScroller.class,"stretch"),scroll);
                var spring=new PausingSpring();spring.notify=()->{};var lease=new ControlCenterTranslation(content);float value=bottom?-80:80;lease.apply(value);
                NativeControlCenter.put(NativeControlCenter.field(ControlCenterStretch.class,"chain"),stretch,spring);
                NativeControlCenter.put(NativeControlCenter.field(ControlCenterStretch.class,"reset"),stretch,NativeControlCenter.method(PausingSpring.class,"pause"));
                NativeControlCenter.put(NativeControlCenter.field(ControlCenterStretch.class,"views"),stretch,java.util.List.of(java.util.List.of(content)));
                NativeControlCenter.put(NativeControlCenter.field(ControlCenterStretch.class,"leases"),stretch,java.util.List.of(java.util.List.of(lease)));
                NativeControlCenter.put(NativeControlCenter.field(ControlCenterStretch.class,"offsets"),stretch,new float[]{value});
                NativeControlCenter.put(NativeControlCenter.field(ControlCenterStretch.class,"carry"),stretch,new float[1]);
                NativeControlCenter.put(NativeControlCenter.field(ControlCenterStretch.class,"topCoefficients"),stretch,new float[]{.144f});
                NativeControlCenter.put(NativeControlCenter.field(ControlCenterStretch.class,"bottomCoefficients"),stretch,new float[]{.12f});
                NativeControlCenter.put(NativeControlCenter.field(ControlCenterStretch.class,"direction"),stretch,bottom?2:1);
                begin(scroll,800,300);assertEquals(value,content.getTranslationY(),.001f);
                MotionEvent down=MotionEvent.obtain(0,0,MotionEvent.ACTION_DOWN,800,300,0);try{assertEquals(!bottom,scroll.intercept(down));}finally{down.recycle();}
                if(bottom)assertTrue(intercept(scroll,800,305));
                touch(scroll,800,bottom?305:295,MotionEvent.ACTION_MOVE);
                root.getViewTreeObserver().dispatchOnPreDraw();
                assertEquals(bottom?1195:5,root.getScrollY());assertTrue(Math.abs(content.getTranslationY())<80);assertTrue(Math.abs(content.getTranslationY())>70);
                touch(scroll,800,bottom?600:0,MotionEvent.ACTION_MOVE);root.getViewTreeObserver().dispatchOnPreDraw();assertEquals(0,content.getTranslationY(),.001f);scroll.reset();
            }
        }finally{owner.pause().stop().destroy();}
    }
    public static final class ReturnChain {
        public final float[] curSpringYArray=new float[2];
        int running;Runnable seed=()->{};
        public void reset(){running=0;java.util.Arrays.fill(curSpringYArray,0);seed.run();}
        public void startRebound(int direction){running=direction;seed.run();}
        public int isSpringSystemRunning(){return running;}
        public ReturnAxis getSpringChainY(){return new ReturnAxis();}
    }
    public static final class ReturnAxis {public java.util.List<ReturnSpring> getAllSprings(){return java.util.List.of(new ReturnSpring());}}
    public static final class ReturnSpring {public void setRestSpeedThreshold(double value){}}
    private ControlCenterStretch returningRegions(FrameLayout root,ReturnChain chain)throws Exception {
        var first=new View(root.getContext());var second=new View(root.getContext());root.addView(first);root.addView(second);
        var stretch=new ControlCenterStretch(root,null,ignored->{});
        for(String name:new String[]{"reset","start","running"}){
            var method=switch(name){case "start"->NativeControlCenter.method(ReturnChain.class,"startRebound",int.class);
                case "running"->NativeControlCenter.method(ReturnChain.class,"isSpringSystemRunning");default->NativeControlCenter.method(ReturnChain.class,"reset");};
            NativeControlCenter.put(NativeControlCenter.field(ControlCenterStretch.class,name),stretch,method);
        }
        NativeControlCenter.put(NativeControlCenter.field(ControlCenterStretch.class,"chain"),stretch,chain);
        NativeControlCenter.put(NativeControlCenter.field(ControlCenterStretch.class,"current"),stretch,NativeControlCenter.field(ReturnChain.class,"curSpringYArray"));
        NativeControlCenter.put(NativeControlCenter.field(ControlCenterStretch.class,"views"),stretch,java.util.List.of(java.util.List.of(first),java.util.List.of(second)));
        NativeControlCenter.put(NativeControlCenter.field(ControlCenterStretch.class,"leases"),stretch,java.util.List.of(java.util.List.of(new ControlCenterTranslation(first)),java.util.List.of(new ControlCenterTranslation(second))));
        NativeControlCenter.put(NativeControlCenter.field(ControlCenterStretch.class,"offsets"),stretch,new float[2]);
        NativeControlCenter.put(NativeControlCenter.field(ControlCenterStretch.class,"carry"),stretch,new float[2]);
        NativeControlCenter.put(NativeControlCenter.field(ControlCenterStretch.class,"topCoefficients"),stretch,new float[]{.1f,.4f});
        NativeControlCenter.put(NativeControlCenter.field(ControlCenterStretch.class,"bottomCoefficients"),stretch,new float[]{.4f,.1f});
        return stretch;
    }
    private float[] regionOffsets(ControlCenterStretch stretch)throws Exception {
        return (float[])NativeControlCenter.get(NativeControlCenter.field(ControlCenterStretch.class,"offsets"),stretch);
    }
    @Test public void largeInterruptedStretchRetiresBeforeTheOppositeEdgeAndCannotGrowBack()throws Exception {
        var owner=Robolectric.buildActivity(Activity.class).setup();
        try {
            var root=new FrameLayout(owner.get());var stretch=returningRegions(root,new ReturnChain());
            // A fast pull can leave more stretch than the entire content scroll range.
            stretch.pose(10000,0);stretch.freeze();stretch.begin(0,800);
            stretch.pose(0,400);assertArrayEquals(new float[]{500,2000},regionOffsets(stretch),.001f);
            stretch.pose(0,200);assertArrayEquals(new float[]{500,2000},regionOffsets(stretch),.001f);
            stretch.pose(-200,800);assertArrayEquals(new float[]{-80,-20},regionOffsets(stretch),.001f);
            stretch.freeze();stretch.begin(800,800);stretch.pose(200,0);
            assertArrayEquals(new float[]{20,80},regionOffsets(stretch),.001f);stretch.clear();
        }finally{owner.pause().stop().destroy();}
    }
    @Test public void lateNativeUpdatesAndOldMonitorCannotEraseTheNextBoundaryReturn()throws Exception {
        var owner=Robolectric.buildActivity(Activity.class).setup();
        try {
            var root=new FrameLayout(owner.get());var chain=new ReturnChain();var stretch=returningRegions(root,chain);
            var changed=NativeControlCenter.method(ControlCenterStretch.class,"springChanged",int.class,float.class);
            chain.seed=()->{NativeControlCenter.call(changed,stretch,0,0f);NativeControlCenter.call(changed,stretch,1,0f);};
            int[] finished={0,0};stretch.pose(200,0);assertTrue(stretch.release(()->finished[0]++));
            assertArrayEquals(new float[]{20,80},regionOffsets(stretch),.001f);
            Runnable old=(Runnable)NativeControlCenter.get(NativeControlCenter.field(ControlCenterStretch.class,"monitor"),stretch);
            stretch.freeze();stretch.begin(0,800);stretch.pose(-200,800);
            NativeControlCenter.call(changed,stretch,0,0f); // Paused native chain delivers a late update.
            assertArrayEquals(new float[]{-80,-20},regionOffsets(stretch),.001f);
            assertTrue(stretch.release(()->finished[1]++));
            Runnable current=(Runnable)NativeControlCenter.get(NativeControlCenter.field(ControlCenterStretch.class,"monitor"),stretch);
            chain.running=0;old.run();assertArrayEquals(new float[]{-80,-20},regionOffsets(stretch),.001f);
            assertArrayEquals(new int[]{0,0},finished);
            chain.running=2;current.run();assertTrue(stretch.hasOffsets());
            NativeControlCenter.call(changed,stretch,0,-40f);assertEquals(-40,regionOffsets(stretch)[0],.001f);
            chain.running=0;current.run();assertFalse(stretch.hasOffsets());assertArrayEquals(new int[]{0,1},finished);
            old.run();current.run();assertArrayEquals(new int[]{0,1},finished);
        }finally{owner.pause().stop().destroy();}
    }
    public static final class NativePanelState {public float expandedFraction;public boolean moving=true;public boolean isFullyExpanded(){return expandedFraction==1;}public boolean isFractionAnimationRunning(){return moving;}}
    @Test public void visuallyExpandedPanelAcceptsScrollingDuringItsInvisibleSpringTail(){
        var nativePanel=new NativePanelState();var transition=new ControlCenterTransition(nativePanel);assertFalse(transition.ready());
        nativePanel.expandedFraction=.8f;assertFalse(transition.ready());nativePanel.expandedFraction=.996f;assertTrue(transition.ready());
        nativePanel.expandedFraction=1;assertTrue(transition.ready());nativePanel.expandedFraction=Float.NaN;assertFalse(transition.ready());
    }
    @Test public void freshUpwardGestureAtBottomBelongsToCollapseEvenWhileReboundIsRunning(){
        var owner=Robolectric.buildActivity(Activity.class).setup().visible();
        try {
            var root=new FrameLayout(owner.get());root.addView(new FrameLayout(owner.get()),new FrameLayout.LayoutParams(1000,1800));owner.get().setContentView(root);
            root.measure(exact(1000),exact(600));root.layout(0,0,1000,600);var scroll=new ControlCenterScroller(root);scroll.resize(1200);
            begin(scroll,800,500);assertTrue(intercept(scroll,800,-900));touch(scroll,800,-900,MotionEvent.ACTION_MOVE);
            touch(scroll,800,-900,MotionEvent.ACTION_UP);assertTrue(root.getScrollY()>=1200);
            begin(scroll,800,300);MotionEvent down=MotionEvent.obtain(0,0,MotionEvent.ACTION_DOWN,800,300,0);
            try{assertFalse(scroll.intercept(down));}finally{down.recycle();}
            assertFalse(intercept(scroll,800,100));assertFalse(scroll.captured());assertFalse(scroll.dragging());
            scroll.reset();
        }finally{owner.pause().stop().destroy();}
    }
    @Test public void edgeFeedbackUsesNativeListHapticAndSkipsPassiveReboundAndReset(){
        var owner=Robolectric.buildActivity(Activity.class).setup().visible();
        try {
            var root=new HapticRoot(owner.get());root.addView(new FrameLayout(owner.get()),new FrameLayout.LayoutParams(1000,1800));owner.get().setContentView(root);
            root.measure(exact(1000),exact(600));root.layout(0,0,1000,600);boolean[] enabled={true};
            var scroll=new ControlCenterScroller(root,ignored->{},()->enabled[0]);scroll.resize(1200);
            begin(scroll,800,200);assertTrue(intercept(scroll,800,400));touch(scroll,800,400,MotionEvent.ACTION_MOVE);
            touch(scroll,800,450,MotionEvent.ACTION_MOVE);assertEquals(java.util.List.of(307),root.feedback);
            touch(scroll,800,450,MotionEvent.ACTION_CANCEL);
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(2));
            assertEquals(java.util.List.of(307),root.feedback);scroll.reset();
            enabled[0]=false;begin(scroll,800,200);assertTrue(intercept(scroll,800,400));touch(scroll,800,400,MotionEvent.ACTION_MOVE);
            assertEquals(java.util.List.of(307),root.feedback);scroll.close();
        }finally{owner.pause().stop().destroy();}
    }
    private static final class HapticRoot extends FrameLayout {
        final java.util.List<Integer> feedback=new java.util.ArrayList<>();
        HapticRoot(android.content.Context context){super(context);}
        @Override public boolean performHapticFeedback(int effect){feedback.add(effect);return true;}
    }
    private int exact(int size){return View.MeasureSpec.makeMeasureSpec(size,View.MeasureSpec.EXACTLY);}
    private void begin(ControlCenterScroller view,float x,float y){MotionEvent event=MotionEvent.obtain(0,0,MotionEvent.ACTION_DOWN,x,y,0);try{view.begin(event);}finally{event.recycle();}}
    private boolean intercept(ControlCenterScroller view,float x,float y){MotionEvent event=MotionEvent.obtain(0,100,MotionEvent.ACTION_MOVE,x,y,0);try{return view.intercept(event);}finally{event.recycle();}}
    private void touch(ControlCenterScroller view,float x,float y,int action){MotionEvent event=MotionEvent.obtain(0,200,action,x,y,0);try{view.touch(event);}finally{event.recycle();}}
}
