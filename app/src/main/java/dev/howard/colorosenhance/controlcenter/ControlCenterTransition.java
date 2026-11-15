package dev.howard.colorosenhance.controlcenter;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import static dev.howard.colorosenhance.controlcenter.NativeControlCenter.*;

/** Read-only visual expansion guard, evaluated once at gesture DOWN. */
final class ControlCenterTransition {
    private final Object component;
    private Method fullyExpanded;
    private Field fraction;
    ControlCenterTransition(Object component){
        this.component=component;
        try{fullyExpanded=method(component.getClass(),"isFullyExpanded");}catch(ReflectiveOperationException ignored){}
        try{fraction=field(component.getClass(),"expandedFraction");}catch(ReflectiveOperationException ignored){}
    }
    boolean ready(){
        if(fullyExpanded!=null && Boolean.TRUE.equals(call(fullyExpanded,component)))return true;
        // Native springs can run long after the visible layout has arrived.
        // Do not wait for every regional spring or for an exact floating-point 1.
        if(fraction!=null)try{float value=((Number)get(fraction,component)).floatValue();return Float.isFinite(value) && value>=.995f;}
        catch(RuntimeException|LinkageError unavailable){fraction=null;}
        return false;
    }
}
