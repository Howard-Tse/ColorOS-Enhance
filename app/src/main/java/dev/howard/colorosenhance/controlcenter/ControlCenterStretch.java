package dev.howard.colorosenhance.controlcenter;

import android.animation.ValueAnimator;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import static dev.howard.colorosenhance.controlcenter.NativeControlCenter.*;

/** Own notification-style spring chain, applied to native QS regions without reparenting. */
final class ControlCenterStretch {
    private static final float TOP_SCALE=.45f,BOTTOM_SCALE=.28f;
    private final ViewGroup root;
    private final Object extension;
    private final Consumer<String> report;
    private Object chain;
    private Method move,start,reset,running;
    private Field current;
    private List<List<View>> views=List.of();
    private List<List<ControlCenterTranslation>> leases=List.of();
    private record Clip(ViewGroup view,boolean children){}
    private final List<Clip> clips=new ArrayList<>();
    private float[] offsets={},topCoefficients={},bottomCoefficients={},carry={};
    private float anchorBase,carryDistance,carryRemaining;
    private int carryDirection=1;
    private boolean bouncing,suppress,unavailable,observing;
    private int direction=1;
    private long bounceStarted;
    private int generation;
    private Runnable completed;
    private Runnable monitor;
    private final ViewTreeObserver.OnPreDrawListener beforeDraw=this::composeBeforeDraw;
    ControlCenterStretch(ViewGroup root,Object extension,Consumer<String> report){this.root=root;this.extension=extension;this.report=report;}
    boolean available(){return chain!=null && !unavailable && !views.isEmpty();}
    void prepare(){
        if(extension==null || unavailable || hasOffsets())return;
        List<List<View>> next=ControlCenterRegions.collect(root);if(next.isEmpty() || next.equals(views))return;
        try{dispose();create(next);}catch(ReflectiveOperationException|RuntimeException|LinkageError failed){report.accept("Layered rebound contract: "+failed);fail();}
    }
    private void create(List<List<View>> next)throws ReflectiveOperationException {
        ClassLoader loader=extension.getClass().getClassLoader();
        Class<?> type=Class.forName("com.coui.appcompat.springchain.COUIGridSpringChain",false,loader);
        Class<?> itemType=Class.forName("com.coui.appcompat.springchain.api.IChainItem",false,loader);
        Class<?> item=Class.forName("com.coui.appcompat.springchain.COUIChainItem",false,loader);
        Class<?> calculatorType=Class.forName(type.getName()+"$TransCalculator",false,loader);
        Class<?> listenerType=Class.forName("com.coui.appcompat.springchain.api.ISpringUpdateListener",false,loader);
        move=method(type,"updateMoveTranslation",float.class,int.class);start=method(type,"startRebound",int.class);
        reset=method(type,"releaseSpringAndLoc");running=method(type,"isSpringSystemRunning");current=field(type,"curSpringYArray");
        Method add=method(type,"addItem",itemType),setCalculator=method(type,"setTranCalculator",calculatorType,int.class);
        Method setListener=method(type,"setSpringUpdateListener",listenerType);
        Object reference=get(field(extension.getClass(),"scrollGridChain"),extension);
        // Validate registry cleanup before constructing a chain (native constructors register debug configs).
        Object referenceY=call(method(type,"getSpringChainY"),reference);
        Object registry=get(field(referenceY.getClass(),"registry"),null);
        Object registryValue=get(field(registry.getClass(),"mSpringConfigMap"),registry);
        if(!(registryValue instanceof java.util.Map<?,?> registryMap))throw new NoSuchFieldException("Spring debug registry");
        chain=type.getConstructor(int.class,int.class,int.class,int.class,int.class,int.class,int.class,int.class)
                .newInstance(300,28,500,22,300,28,500,22);
        for(String axis:new String[]{"getSpringChainX","getSpringChainY"}){
            Object own=call(method(type,axis),chain),nativeAxis=call(method(type,axis),reference);
            for(String key:new String[]{"mMainSpringConfig","mAttachmentSpringConfig"}){
                Object ownConfig=get(field(own.getClass(),key),own),nativeConfig=get(field(nativeAxis.getClass(),key),nativeAxis);
                // Remove only the four new config objects registered by our constructor.
                registryMap.remove(ownConfig);
                for(String property:new String[]{"tension","friction"})field(ownConfig.getClass(),property).setDouble(ownConfig,field(nativeConfig.getClass(),property).getDouble(nativeConfig));
            }
        }
        Object calculator=call(method(extension.getClass(),"getTransCalculator"),extension);
        call(setCalculator,chain,calculator,1);call(setCalculator,chain,calculator,2);
        views=next;offsets=new float[next.size()];carry=new float[next.size()];
        List<List<ControlCenterTranslation>> targets=new ArrayList<>();
        for(int row=0;row<next.size();row++){
            targets.add(next.get(row).stream().map(ControlCenterTranslation::new).toList());
            Object virtual=item.getConstructor(View.class,int.class,int.class,int.class,int.class,boolean.class).newInstance(null,0,row,1,1,false);
            call(add,chain,virtual);
        }
        leases=targets;
        Object ownerChain=chain;
        Object listener=Proxy.newProxyInstance(loader,new Class<?>[]{listenerType},(proxy,callback,args)->{
            if(callback.getDeclaringClass()==Object.class)return switch(callback.getName()){case "hashCode"->System.identityHashCode(proxy);case "equals"->proxy==args[0];default->"ControlCenterSpringListener";};
            if(chain==ownerChain && "onUpdate".equals(callback.getName()) && ((Number)args[2]).intValue()==direction)
                try{springChanged(((Number)args[0]).intValue(),((Number)args[1]).floatValue());}catch(RuntimeException|LinkageError failed){fail();}
            return null;
        });
        call(setListener,chain,listener);
        topCoefficients=coefficients(1);bottomCoefficients=coefficients(2);
        report.accept("Layered rebound: notification-center chain; regions="+views.size());
    }
    private float[] coefficients(int dir){
        suppress=true;
        try{call(reset,chain);call(move,chain,1f,dir);float[] array=(float[])get(current,chain);float[] values=java.util.Arrays.copyOf(array,views.size());for(int i=0;i<values.length;i++)values[i]*=dir==1?TOP_SCALE:BOTTOM_SCALE;call(reset,chain);return values;}
        finally{suppress=false;}
    }
    void freeze(){
        stopMonitor();bouncing=false;completed=null;
        if(available()){
            // Our offsets are the visual snapshot. Reset the private engine's
            // old targets/caches so the opposite edge cannot inherit them.
            boolean previous=suppress;suppress=true;
            try{call(reset,chain);}catch(RuntimeException|LinkageError failed){fail();}finally{suppress=previous;}
        }
    }
    float position(int range){
        // A carried visual stretch is not extra scroll distance to pay back.
        return Math.max(0,Math.min(range,root.getScrollY()));
    }
    void begin(float base,int range){
        if(!available())return;anchorBase=base;carryDirection=direction;float largest=0;
        for(int i=0;i<offsets.length;i++){carry[i]=offsets[i];largest=Math.max(largest,Math.abs(offsets[i]));}
        carryDistance=Math.max(64*root.getResources().getDisplayMetrics().density,largest*1.5f);
        float inwardSpace=carryDirection==1?range-base:base;
        if(inwardSpace>0)carryDistance=Math.min(carryDistance,inwardSpace);
        carryRemaining=largest>.05f?1:0;
    }
    void pose(float raw,float base){
        if(!available())return;
        float weight=Math.min(carryRemaining,carryWeight(base,anchorBase,carryDirection,carryDistance));
        if(carryRemaining>0 && weight==0)java.util.Arrays.fill(carry,0);
        carryRemaining=weight;
        if(raw==0 && !hasOffsets() && weight==0)return;
        allowRegionOverflow();
        if(raw!=0)direction=raw>0?1:2;
        float[] coefficients=direction==1?topCoefficients:bottomCoefficients;
        for(int i=0;i<offsets.length;i++)apply(i,coefficients[i]*raw+carry[i]*weight);
        if(!hasOffsets()){for(var row:leases)for(var lease:row)lease.restore();restoreClipping();}
    }
    static float carryWeight(float base,float anchor,int direction,float distance){
        float inward=direction==1?base-anchor:anchor-base;
        return distance<=0?0:Math.max(0,Math.min(1,1-inward/distance));
    }
    private void apply(int row,float value){
        if(row<0 || row>=offsets.length || !Float.isFinite(value))return;
        offsets[row]=value;for(var lease:leases.get(row))lease.stage(value);
        // Coalesced by ViewRoot: at most one draw request even if the native
        // spring chain updates several regions before the next frame.
        root.postInvalidateOnAnimation();
    }
    private void springChanged(int row,float value){if(!suppress && bouncing)apply(row,value);}
    private boolean composeBeforeDraw(){
        // QS opening springs can write the same translations after our spring
        // callback. Commit their latest base plus our displacement once before
        // drawing, so callback order cannot alternate the visible position.
        try{if(observing)for(var row:leases)for(var lease:row)lease.sync();}
        catch(RuntimeException|LinkageError failed){fail();}
        return true;
    }
    private void allowRegionOverflow(){
        if(!observing){root.getViewTreeObserver().addOnPreDrawListener(beforeDraw);observing=true;}
        if(!clips.isEmpty())return;
        java.util.Set<ViewGroup> parents=java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        for(var row:views)for(View view:row)for(var parent=view.getParent();parent instanceof ViewGroup group && group!=root;parent=group.getParent())parents.add(group);
        // Padding clipping belongs to the native horizontal/expansion controller.
        // Its latest false can be indistinguishable from our temporary false;
        // restoring a snapshot would clip the next opening's negative row offset.
        for(ViewGroup group:parents){clips.add(new Clip(group,group.getClipChildren()));group.setClipChildren(false);}
    }
    private void restoreClipping(){
        if(observing){observing=false;if(root.getViewTreeObserver().isAlive())root.getViewTreeObserver().removeOnPreDrawListener(beforeDraw);}
        for(Clip clip:clips){if(!clip.view().getClipChildren())clip.view().setClipChildren(clip.children());}clips.clear();
    }
    boolean release(Runnable done){
        if(!available())return false;freeze();
        if(!hasOffsets() || !ValueAnimator.areAnimatorsEnabled()){clear();done.run();return true;}
        try{
            float[] array=(float[])get(current,chain);System.arraycopy(offsets,0,array,0,offsets.length);
            // Native initialization emits synchronous updates while seeding.
            // Keep our complete snapshot until the new engine is armed.
            suppress=true;try{call(start,chain,direction);}finally{suppress=false;}
            bouncing=true;completed=done;bounceStarted=android.os.SystemClock.uptimeMillis();
            Object axis=call(method(chain.getClass(),"getSpringChainY"),chain);
            for(Object spring:(List<?>)call(method(axis.getClass(),"getAllSprings"),axis))call(method(spring.getClass(),"setRestSpeedThreshold",double.class),spring,3d);
            int token=generation;monitor=()->monitor(token);root.postOnAnimation(monitor);return true;
        }catch(ReflectiveOperationException|RuntimeException|LinkageError failed){fail();return false;}
    }
    private void monitor(int token){
        if(token!=generation || !bouncing || !available())return;
        try{
            if((int)call(running,chain)==0 || android.os.SystemClock.uptimeMillis()-bounceStarted>2000){Runnable done=completed;clear();if(done!=null)done.run();}
            else root.postOnAnimation(monitor);
        }catch(RuntimeException|LinkageError failed){Runnable done=completed;fail();if(done!=null)done.run();}
    }
    boolean hasOffsets(){for(float value:offsets)if(Math.abs(value)>.05f)return true;return false;}
    private void stopMonitor(){generation++;if(monitor!=null){root.removeCallbacks(monitor);monitor=null;}}
    void clear(){
        stopMonitor();bouncing=false;completed=null;suppress=true;
        try{if(chain!=null)call(reset,chain);}finally{suppress=false;for(var row:leases)for(var lease:row)lease.restore();restoreClipping();java.util.Arrays.fill(offsets,0);java.util.Arrays.fill(carry,0);carryDistance=0;carryRemaining=0;}
    }
    void dispose(){clear();chain=null;views=List.of();leases=List.of();offsets=new float[0];}
    private void fail(){
        unavailable=true;stopMonitor();bouncing=false;completed=null;suppress=true;
        try{if(chain!=null && reset!=null)call(reset,chain);}catch(RuntimeException|LinkageError ignored){}
        for(var row:leases)for(var lease:row)lease.restore();restoreClipping();chain=null;offsets=new float[0];suppress=false;
        report.accept("Layered rebound unavailable; uniform native curve retained");
    }
}
