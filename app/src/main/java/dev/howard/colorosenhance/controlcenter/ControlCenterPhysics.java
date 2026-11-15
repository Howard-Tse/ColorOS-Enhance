package dev.howard.colorosenhance.controlcenter;

import android.animation.TimeInterpolator;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.widget.OverScroller;
import java.lang.reflect.Method;
import java.util.function.Consumer;
import static dev.howard.colorosenhance.controlcenter.NativeControlCenter.*;

/** Create a separate engine via the notification center's factory; never borrow its live scroller. */
final class ControlCenterPhysics {
    private final Object engine;
    private final Method abort,advance,y,finished,fling,velocity;
    final TimeInterpolator rebound;
    final long reboundDuration;
    final float edgeFactor;
    final Object notificationExtension;
    @android.annotation.SuppressLint("DiscouragedApi")
    ControlCenterPhysics(View root,Consumer<String> report){
        Object candidate=null,extension=null;TimeInterpolator curve=null;float factor=.24f;
        try {
            int id=root.getResources().getIdentifier("notification_stack_scroller","id","com.android.systemui");
            View stack=id==0?null:root.getRootView().findViewById(id);
            if(stack!=null){
                extension=get(field(stack.getClass(),"mExt"),stack);
                candidate=call(method(extension.getClass(),"createOplusSpringOverScroller",android.content.Context.class),extension,root.getContext());
                validate(candidate);
            }
        }catch(ReflectiveOperationException|RuntimeException|LinkageError unavailable){
            if(candidate!=null)try{call(method(candidate.getClass(),"abortAnimation"),candidate);}catch(ReflectiveOperationException|RuntimeException ignored){}
            candidate=null;curve=null;
        }
        if(candidate!=null && extension!=null){
            try{Object nativeCurve=get(field(extension.getClass(),"REBOUND_INTERPOLATOR"),null);if(nativeCurve instanceof TimeInterpolator interpolator)curve=interpolator;}
            catch(ReflectiveOperationException|RuntimeException|LinkageError unavailable){}
            try{Object calculator=call(method(extension.getClass(),"getTransCalculator"),extension);
                float nativeFactor=(float)call(method(calculator.getClass(),"getTrans",float.class,int.class,int.class),calculator,1f,0,1);
                if(Float.isFinite(nativeFactor) && nativeFactor>0 && nativeFactor<=1)factor=nativeFactor;
            }catch(ReflectiveOperationException|RuntimeException|LinkageError unavailable){}
        }
        engine=candidate==null?new OverScroller(root.getContext()):candidate;
        notificationExtension=candidate==null?null:extension;
        rebound=curve==null?new DecelerateInterpolator(1.5f):curve;reboundDuration=curve==null?280:850;
        edgeFactor=factor;
        try{
            Class<?> type=engine.getClass();abort=method(type,"abortAnimation");advance=method(type,"computeScrollOffset");
            y=method(type,"getCurrY");finished=method(type,"isFinished");velocity=method(type,"getCurrVelocity");
            fling=method(type,"fling",int.class,int.class,int.class,int.class,int.class,int.class,int.class,int.class,int.class,int.class);
        }catch(ReflectiveOperationException incompatible){throw new IllegalStateException(incompatible);}
        report.accept(candidate==null?"Scroll engine: Android fallback":"Scroll engine: notification center; native rebound="+(curve!=null));
    }
    private static void validate(Object candidate)throws ReflectiveOperationException {
        if(candidate==null)throw new NoSuchMethodException("Notification scroll factory");
        Class<?> type=candidate.getClass();
        for(String name:new String[]{"abortAnimation","computeScrollOffset","getCurrY","isFinished","getCurrVelocity"})method(type,name);
        method(type,"fling",int.class,int.class,int.class,int.class,int.class,int.class,int.class,int.class,int.class,int.class);
    }
    void abort(){call(abort,engine);}
    boolean advance(){return (boolean)call(advance,engine);}
    int y(){return (int)call(y,engine);}
    boolean finished(){return (boolean)call(finished,engine);}
    float speed(){return Math.abs(((Number)call(velocity,engine)).floatValue());}
    void fling(int y,int speed,int range,int over){call(fling,engine,0,y,1,speed,0,0,0,range,0,over);}
    void edge(int y,int edge,int range,int over){
        int speed=Math.round(speed());int excursion=Math.min(over,Math.round(speed/12f));
        call(fling,engine,0,y,1,edge==0?-speed:speed,0,0,edge==0?-excursion:0,edge==0?range:range+excursion,0,0);
    }
    static float mapEdge(float raw,int range,float factor){return raw<0?raw*factor:raw>range?range+(raw-range)*factor:raw;}
    static float unmapEdge(float shown,int range,float factor){return shown<0?shown/factor:shown>range?range+(shown-range)/factor:shown;}
}
