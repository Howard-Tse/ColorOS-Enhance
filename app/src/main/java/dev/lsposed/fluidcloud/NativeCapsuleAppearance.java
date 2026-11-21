package dev.lsposed.fluidcloud;

import android.graphics.drawable.GradientDrawable;
import android.graphics.Rect;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

/** Only the OEM capsule's own border and switch-indicator updates are observed. */
final class NativeCapsuleAppearance {
    interface Lookup { Class<?> load(String name) throws ClassNotFoundException; }
    static final String VIEWS="com.oplus.systemui.plugins.seedling.capsule.ui.view.";
    final Class<?> capsule,container;
    final boolean legacy;
    final Method dark, inflated, colorChanged, moons, stateChanged;
    private final Field drawable, intensity, color, callbackView;
    private final Method left,right;
    private final Field contentState;
    private final Set<View> strokes=Collections.newSetFromMap(new WeakHashMap<>());
    private final WeakHashMap<View,Boolean> moonRequests=new WeakHashMap<>();
    private final WeakHashMap<View,Chrome> chrome=new WeakHashMap<>();
    private record Chrome(GradientDrawable background, GradientDrawable foreground,
                          GradientDrawable appliedBackground, GradientDrawable appliedForeground,
                          CloudCorners.Style style, float density) { }
    private boolean replayingMoons;
    NativeCapsuleAppearance(Lookup lookup) throws ReflectiveOperationException {
        capsule=lookup.load(VIEWS+"CapsuleView");container=lookup.load(VIEWS+"CapsuleContainer");
        if(!ViewGroup.class.isAssignableFrom(capsule)||!ViewGroup.class.isAssignableFrom(container))
            throw new NoSuchMethodException("Capsule view types");
        boolean alternate = ColorOs16Plugin.capsule(container);
        Field foreground;boolean old;
        if (alternate) {
            foreground = Reflect.field(capsule, "p", GradientDrawable.class);
            old = true;
        } else try{foreground=Reflect.field(capsule,"f",GradientDrawable.class);old=false;}
        catch(NoSuchFieldException missing){foreground=Reflect.field(capsule,"q",GradientDrawable.class);old=true;}
        legacy=old;drawable=foreground;
        intensity=Reflect.field(capsule,alternate?"n":old?"o":"d",float.class);
        color=Reflect.field(capsule,alternate?"o":old?"p":"e",int.class);
        dark=Reflect.method(capsule,"b",void.class,float.class);inflated=Reflect.method(capsule,"onFinishInflate",void.class);
        moons=Reflect.method(container,"setMoonViewShown",void.class,boolean.class);
        left=Reflect.method(container,"getLeftMoon",ImageView.class);right=Reflect.method(container,"getRightMoon",ImageView.class);
        contentState=Reflect.field(container,alternate?"v":old?"x":"m",Enum.class);
        stateChanged=Reflect.method(container,old?"i":"h",void.class,
                lookup.load(alternate?"k4.f":old?"y4.f":"com.oplus.systemui.plugins.seedling.capsule.ui.model.f"),boolean.class);
        // This version delivers sampled colors through CapsuleView.b(float) itself.
        Class<?> callback=alternate?null:lookup.load(old?"w4.d":"com.oplus.systemui.plugins.seedling.capsule.data.repo.d");
        colorChanged=callback==null?null:Reflect.method(callback,old?"b":"m",void.class,Bundle.class);
        callbackView=callback==null?null:Reflect.field(callback,old?"b":"c",Object.class);
        if(inflated.getDeclaringClass()!=capsule||dark.getDeclaringClass()!=capsule||moons.getDeclaringClass()!=container)
            throw new NoSuchMethodException("Inherited capsule hook is not supported");
    }
    Object colorOwner(Object callback){return Reflect.get(callbackView,callback);}
    void corners(View view,CloudCorners.Style style,boolean hideStroke){
        if(!capsule.isInstance(view))return;
        Chrome old=chrome.get(view);
        if(!style.enabled()){
            if(old!=null){
                chrome.remove(view);
                if(view.getBackground()==old.appliedBackground())view.setBackground(old.background());
                if(view.getForeground()==old.appliedForeground()){
                    view.setForeground(old.foreground());
                    if(Reflect.get(drawable,view)==old.appliedForeground())writeForeground(view,old.foreground());
                    updateStroke(view,hideStroke);
                }
            }
            return;
        }
        if(!(view.getBackground() instanceof GradientDrawable bg)
                || !(view.getForeground() instanceof GradientDrawable fg) || Reflect.get(drawable,view)!=fg)return;
        // The capsule resources on both firmware generations are solid fills with a solid stroke.
        if (bg.getColor() == null || fg.getColor() == null || bg.getCornerRadii() != null
                || fg.getCornerRadii() != null) return;
        float density=view.getResources().getDisplayMetrics().density;
        if(old!=null && bg==old.appliedBackground() && fg==old.appliedForeground()
                && style.equals(old.style()) && density==old.density())return;
        CapsuleChromeDrawable newBg = new CapsuleChromeDrawable(bg, style.requested(density), style.continuous());
        CapsuleChromeDrawable newFg = new CapsuleChromeDrawable(fg, style.requested(density), style.continuous());
        Rect bgBounds=new Rect(bg.getBounds()),fgBounds=new Rect(fg.getBounds());
        GradientDrawable nativeBg=old!=null&&bg==old.appliedBackground()?old.background():bg;
        GradientDrawable nativeFg=old!=null&&fg==old.appliedForeground()?old.foreground():fg;
        chrome.put(view,new Chrome(nativeBg,nativeFg,newBg,newFg,style,density));
        view.setBackground(newBg);
        view.setForeground(newFg);
        writeForeground(view,newFg);
        newBg.setBounds(bgBounds);
        newFg.setBounds(fgBounds);
        updateStroke(view,hideStroke);
    }
    private void writeForeground(View view,GradientDrawable foreground){
        try{drawable.set(view,foreground);}catch(IllegalAccessException error){throw new IllegalStateException(error);}
    }
    private void updateStroke(View view,boolean hideStroke){
        Reflect.call(dark,view,((Number)Reflect.get(intensity,view)).floatValue());
        if(hideStroke)stroke(view,true);
    }
    void stroke(Object owner,boolean hide){
        if(!(owner instanceof View view)||!capsule.isInstance(owner))return;
        if(!hide){
            if(strokes.remove(view))Reflect.call(dark,view,((Number)Reflect.get(intensity,view)).floatValue());
            return;
        }
        GradientDrawable foreground=(GradientDrawable)Reflect.get(drawable,view);
        if(foreground!=null){strokes.add(view);foreground.setStroke(0,(int)Reflect.get(color,view));}
    }
    boolean moonRequest(View view,boolean requested,boolean hide){
        if(replayingMoons)return requested;
        if(hide){moonRequests.put(view,requested);return false;}
        moonRequests.remove(view);return requested;
    }
    void afterState(View view,boolean hide){
        if(hide && ((Enum<?>)Reflect.get(contentState,view)).name().equals(legacy?"HIDE":"d"))
            moonRequests.put(view,false);
    }
    void refresh(View root,boolean hideStroke,boolean hideArrows){
        refresh(root,hideStroke,hideArrows,CloudCorners.Style.OEM);
    }
    void refresh(View root,boolean hideStroke,boolean hideArrows,CloudCorners.Style style){
        if(capsule.isInstance(root)){corners(root,style,hideStroke);stroke(root,hideStroke);}
        if(container.isInstance(root)){
            Boolean requested=moonRequests.get(root);
            if(hideArrows||requested!=null){
                if(requested==null)requested=((View)Reflect.call(left,root)).getVisibility()==View.VISIBLE
                        ||((View)Reflect.call(right,root)).getVisibility()==View.VISIBLE;
                invokeMoons(root,moonRequest(root,requested,hideArrows));
            }
        }
        if(root instanceof ViewGroup group)for(int i=0;i<group.getChildCount();i++)refresh(group.getChildAt(i),hideStroke,hideArrows,style);
    }
    void restore(){
        for(View view:new ArrayList<>(chrome.keySet()))corners(view,CloudCorners.Style.OEM,false);
        restoreDisabled(false,false);
    }
    void restoreDisabled(boolean hideStroke,boolean hideArrows){
        if(!hideStroke)for(View view:new ArrayList<>(strokes))stroke(view,false);
        if(!hideArrows){
            var pending=new WeakHashMap<>(moonRequests);moonRequests.clear();
            for(var entry:pending.entrySet())invokeMoons(entry.getKey(),entry.getValue());
        }
    }
    private void invokeMoons(View view,boolean shown){
        boolean previous=replayingMoons;replayingMoons=true;
        try{Reflect.call(moons,view,shown);}finally{replayingMoons=previous;}
    }
}
