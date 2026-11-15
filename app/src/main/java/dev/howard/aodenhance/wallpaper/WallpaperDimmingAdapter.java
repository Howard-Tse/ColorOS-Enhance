package dev.howard.aodenhance.wallpaper;

import dev.howard.aodenhance.Config;
import java.lang.ref.WeakReference;
import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Runs uniform writes on the engine thread, without polling or changing its frame rate. */
final class WallpaperDimmingAdapter {
    private final WallpaperContract contract;
    private final Supplier<Config> configuration;
    private final Consumer<Throwable> errors;
    private final Map<Object, Target> renderers=new WeakHashMap<>();
    private static final class Target {
        final int pass;
        float applied=Float.NaN;
        Target(int pass) {this.pass=pass;}
    }
    WallpaperDimmingAdapter(WallpaperContract contract,Supplier<Config> configuration,Consumer<Throwable> errors) {
        this.contract=contract;this.configuration=configuration;this.errors=errors;
    }

    void prepare(Object data) throws ReflectiveOperationException {
        Object layers=contract.getLayers.invoke(data);
        boolean hasClock=false;
        for(int i=0;i<Array.getLength(layers);i++) {
            String name=(String)contract.layerName.invoke(Array.get(layers,i));
            if(name!=null && name.toLowerCase(java.util.Locale.ROOT).contains("clock"))hasClock=true;
        }
        if(!hasClock)return;
        for(int i=0;i<Array.getLength(layers);i++) {
            Object layer=Array.get(layers,i);
            if(!"background".equals(contract.layerName.invoke(layer)))continue;
            Object passes=contract.getRender.invoke(layer);
            int count=Array.getLength(passes);
            if(count==0)continue;
            Object last=Array.get(passes,count-1);
            String original=(String)contract.getFs.invoke(last);
            // Outputs consumed by other passes may also feed the clock material. Leave those native.
            if(!displayOnly(last))continue;
            String patched=BackgroundShader.patch(original);
            if(patched==null)continue;
            Object[] values=new Object[contract.passValues.length];
            for(int p=0;p<values.length;p++)values[p]=contract.passValues[p].invoke(last);
            values[1]=patched;
            Object replacement=contract.copyPass.invoke(last,values);
            Object changed=Array.newInstance(passes.getClass().getComponentType(),count);
            System.arraycopy(passes,0,changed,0,count);
            Array.set(changed,count-1,replacement);
            contract.setRender.invoke(layer,changed);
        }
    }
    private boolean displayOnly(Object pass) throws ReflectiveOperationException {
        Object values=contract.passValues[3].invoke(pass);
        if(!(values instanceof Map<?,?> uniforms))return false;
        for(Object uniform:uniforms.values()) {
            String type=String.valueOf(contract.uniformType.invoke(uniform));
            if("Out".equalsIgnoreCase(type) || "FBO".equalsIgnoreCase(type))return false;
        }
        return true;
    }

    void update(Object renderer) {
        try {
            Target target;
            synchronized(renderers) {
                target=renderers.get(renderer);
                if(target==null) {
                    Object layer=contract.getLayer.invoke(renderer);
                    int index=-1;
                    if("background".equals(contract.layerName.invoke(layer))) {
                        Object passes=contract.getRender.invoke(layer);
                        int last=Array.getLength(passes)-1;
                        if(last>=0 && ((String)contract.getFs.invoke(Array.get(passes,last))).contains(BackgroundShader.UNIFORM))index=last;
                    }
                    target=new Target(index);renderers.put(renderer,target);
                }
            }
            if(target.pass<0)return;
            Config config=configuration.get();
            boolean panoramic=false;
            try {
                Object state=contract.scene.get(null);
                panoramic=state!=null && Boolean.TRUE.equals(contract.panoramic.invoke(state));
            } catch(ReflectiveOperationException | RuntimeException failure) {errors.accept(failure);}
            float dim=BackgroundShader.amount(config.enabled,config.dimEnabled,config.dim,panoramic);
            if(Float.compare(dim,target.applied)==0)return;
            Object pass=contract.getPass.invoke(renderer,target.pass);
            if(pass==null)return; // Not compiled yet; retry at the next native update.
            contract.setParameter.invoke(pass,BackgroundShader.UNIFORM,new Object[]{dim});
            target.applied=dim;
        } catch(ReflectiveOperationException | RuntimeException | LinkageError failure) {errors.accept(failure);}
    }
    void forget(Object renderer) {synchronized(renderers){renderers.remove(renderer);}}
    void refresh() {
        ArrayList<Object> targets;
        synchronized(renderers) {
            targets=new ArrayList<>();
            for(var entry:renderers.entrySet())if(entry.getValue().pass>=0)targets.add(entry.getKey());
        }
        for(Object renderer:targets) {
            WeakReference<Object> weak=new WeakReference<>(renderer);
            try {
                Object engine=contract.getEngine.invoke(renderer);
                contract.submit(engine,()->{
                    Object current=weak.get();
                    if(current!=null)update(current);
                });
                contract.postDraw.invoke(engine);
            } catch(ReflectiveOperationException | RuntimeException | LinkageError failure) {errors.accept(failure);}
        }
    }
}
