package dev.howard.aodenhance.wallpaper;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Constructor;
import java.lang.reflect.Proxy;
import java.util.Arrays;

/** Resolve the complete model/render/scene contract before installing any hook. */
final class WallpaperContract {
    interface Lookup { Class<?> load(String name) throws ClassNotFoundException; }
    final Method update, destroy, getLayers, layerName, getRender, setRender, getFs, copyPass;
    final Method[] passValues;
    final Method getLayer, getPass, getEngine, setParameter, post, postDraw, panoramic;
    final Method uniformType;
    final Field scene;
    final Constructor<?> rendererConstructor;
    private final Class<?> taskType;
    private final Constructor<?> taskOptions;
    private final Object deferred;

    WallpaperContract(Lookup lookup) throws ReflectiveOperationException {
        this(lookup,false);
    }
    WallpaperContract(Lookup lookup, boolean modern) throws ReflectiveOperationException {
        String models=modern ? "com.oplus.vfxsdk.coe2.common." : "com.oplus.vfxsdk.common.";
        String runtime=modern ? "com.oplus.vfxsdk.naive2." : "com.oplus.vfxsdk.naive.";
        Class<?> data=lookup.load(models+"COEData");
        Class<?> layer=lookup.load(models+"Layer");
        Class<?> passModel=lookup.load(models+"RendPass");
        Class<?> uniform=lookup.load(models+"Uniform");
        Class<?> uniformKind=lookup.load(models+"UniformType");
        Class<?> renderer=lookup.load(runtime+"coe.engine.BaseRenderer");
        Class<?> coeRenderer=lookup.load(runtime+"parse.COERenderer");
        Class<?> pass=lookup.load(runtime+"coe.engine.Pass");
        Class<?> engine=lookup.load(runtime+"coe.engine.NativeEngine");
        Class<?> sceneType=lookup.load("com.heytap.colorfulengine.lifecycle.model.SceneInfo");
        Class<?> lifecycle=lookup.load("com.heytap.colorfulengine.lifecycle.ColorfulLifecycleManager");
        Constructor<?> selectedConstructor=null;
        for(Constructor<?> constructor:coeRenderer.getDeclaredConstructors()) {
            Class<?>[] args=constructor.getParameterTypes();
            if(args.length==8 && args[0]==engine && args[1]==float.class && args[2]==float.class
                    && args[4]==data && args[5]==int.class && args[6]==boolean.class) {
                if(selectedConstructor!=null)throw new NoSuchMethodException("Ambiguous wallpaper renderer constructor");
                selectedConstructor=constructor;
            }
        }
        if(selectedConstructor==null)throw new NoSuchMethodException("Wallpaper renderer constructor unavailable");
        selectedConstructor.setAccessible(true);rendererConstructor=selectedConstructor;
        update=unique(renderer,"onUpdate",void.class,double.class);
        destroy=unique(renderer,"onDestroy",void.class);
        getLayers=unique(data,"getLayers",Array.newInstance(layer,0).getClass());
        layerName=unique(layer,"getName",String.class);
        getRender=unique(layer,"getRender",Array.newInstance(passModel,0).getClass());
        setRender=unique(layer,"setRender",void.class,getRender.getReturnType());
        getFs=unique(passModel,"getFs",String.class);
        String[] getters={"getVs","getFs","getOrder","getUniforms","getStatus","getCs","getEffects"};
        passValues=new Method[getters.length];
        Class<?>[] copyTypes=new Class<?>[getters.length];
        for(int i=0;i<getters.length;i++) {
            passValues[i]=uniqueNamedNoArgs(passModel,getters[i]);
            copyTypes[i]=passValues[i].getReturnType();
        }
        copyPass=unique(passModel,"copy",passModel,copyTypes);
        uniformType=unique(uniform,"getType",uniformKind);
        getLayer=unique(renderer,"getLayer",layer);
        getPass=unique(renderer,"getPass",pass,int.class);
        getEngine=unique(renderer,"getEngine",engine);
        setParameter=unique(pass,"setParameter",void.class,String.class,Object[].class);
        if(modern) {
            taskType=lookup.load(runtime+"coe.api.ITask");
            if(!taskType.isInterface() || taskType.getMethods().length!=1)throw new NoSuchMethodException("Wallpaper task contract changed");
            unique(taskType,"doTask",void.class);
            Class<?> options=lookup.load(runtime+"coe.api.TaskOptions");
            Class<?> execution=lookup.load(runtime+"coe.api.ExecutionType");
            if(!execution.isEnum())throw new NoSuchFieldException("Wallpaper execution type unavailable");
            deferred=execution.getField("Deferred").get(null);
            taskOptions=options.getDeclaredConstructor(String.class,int.class,long.class,long.class,int.class,
                    execution,boolean.class,boolean.class,boolean.class,long.class);
            taskOptions.setAccessible(true);
            post=unique(engine,"postTask",void.class,taskType,options);
        } else {
            taskType=null;taskOptions=null;deferred=null;
            post=unique(engine,"post",long.class,Runnable.class);
        }
        postDraw=unique(engine,"postDraw",void.class);
        panoramic=unique(sceneType,"isPanoramicAod",boolean.class);
        Field selected=null;
        for(Field field:lifecycle.getDeclaredFields()) if(Modifier.isStatic(field.getModifiers()) && field.getType()==sceneType) {
            if(selected!=null)throw new NoSuchFieldException("Ambiguous wallpaper scene source");
            selected=field;
        }
        if(selected==null)throw new NoSuchFieldException("Wallpaper scene source unavailable");
        selected.setAccessible(true);scene=selected;
    }
    void submit(Object engine,Runnable callback) throws ReflectiveOperationException {
        if(taskType==null) {post.invoke(engine,callback);return;}
        Object task=Proxy.newProxyInstance(taskType.getClassLoader(),new Class<?>[]{taskType},(proxy,method,args)-> {
            if(method.getDeclaringClass()==Object.class)return switch(method.getName()) {
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy==args[0];
                case "toString" -> "ColorOS Enhance background update";
                default -> throw new IllegalStateException("Unknown task identity method");
            };
            if(!method.getName().equals("doTask") || method.getParameterCount()!=0)throw new IllegalStateException("Unknown wallpaper task callback");
            callback.run();return null;
        });
        // One deferred engine-thread update with a GL context; never repeat or change cadence.
        Object options=taskOptions.newInstance("ColorOS Enhance background",0,0L,0L,0,deferred,true,true,false,0L);
        post.invoke(engine,task,options);
    }
    private static Method uniqueNamedNoArgs(Class<?> owner,String name) throws NoSuchMethodException {
        Method selected=null;
        for(Method candidate:owner.getDeclaredMethods()) if(candidate.getName().equals(name) && candidate.getParameterCount()==0) {
            if(selected!=null)throw new NoSuchMethodException("Ambiguous "+name);
            selected=candidate;
        }
        if(selected==null)throw new NoSuchMethodException(name);
        selected.setAccessible(true);return selected;
    }
    private static Method unique(Class<?> owner,String name,Class<?> result,Class<?>...args) throws NoSuchMethodException {
        Method selected=null;
        for(Method candidate:owner.getDeclaredMethods()) {
            if((name==null || name.equals(candidate.getName())) && candidate.getReturnType()==result
                    && Arrays.equals(args,candidate.getParameterTypes())) {
                if(selected!=null)throw new NoSuchMethodException("Ambiguous "+owner.getName()+"."+name);
                selected=candidate;
            }
        }
        if(selected==null)throw new NoSuchMethodException(owner.getName()+"."+name);
        selected.setAccessible(true);return selected;
    }
}
