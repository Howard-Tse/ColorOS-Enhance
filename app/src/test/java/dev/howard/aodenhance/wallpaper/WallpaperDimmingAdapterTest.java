package dev.howard.aodenhance.wallpaper;

import dev.howard.aodenhance.Config;
import java.util.*;
import org.junit.Test;
import static org.junit.Assert.*;

/** Synthetic contracts; no proprietary firmware or shaders are shipped with the tests. */
public class WallpaperDimmingAdapterTest {
    private Config configuration=Config.from(Map.of("enabled",true,"dim_enabled",true,"dim",50));
    private final List<Throwable> errors=new ArrayList<>();
    private WallpaperContract contract() throws Exception {
        Map<String,Class<?>> types=Map.ofEntries(
                Map.entry("com.oplus.vfxsdk.common.COEData",Data.class),Map.entry("com.oplus.vfxsdk.common.Layer",Layer.class),
                Map.entry("com.oplus.vfxsdk.common.RendPass",ModelPass.class),Map.entry("com.oplus.vfxsdk.common.Uniform",Uniform.class),
                Map.entry("com.oplus.vfxsdk.common.UniformType",Kind.class),Map.entry("com.oplus.vfxsdk.naive.COEEngine",Coe.class),
                Map.entry("com.oplus.vfxsdk.naive.coe.engine.BaseRenderer",Renderer.class),
                Map.entry("com.oplus.vfxsdk.naive.parse.COERenderer",CoeRenderer.class),
                Map.entry("com.oplus.vfxsdk.naive.coe.engine.Pass",Pass.class),Map.entry("com.oplus.vfxsdk.naive.coe.engine.NativeEngine",Engine.class),
                Map.entry("com.heytap.colorfulengine.lifecycle.model.SceneInfo",Scene.class),
                Map.entry("com.heytap.colorfulengine.lifecycle.ColorfulLifecycleManager",Lifecycle.class));
        return new WallpaperContract(name->{Class<?> value=types.get(name);if(value==null)throw new ClassNotFoundException(name);return value;});
    }
    private WallpaperDimmingAdapter adapter() throws Exception {return new WallpaperDimmingAdapter(contract(),()->configuration,errors::add);}
    @Test public void onlyFinalDisplayBackgroundPassIsCopiedAndPreparationIsIdempotent() throws Exception {
        var adapter=adapter();var intermediate=new ModelPass(Kind.FBO);var finalPass=new ModelPass(Kind.UseFBO);
        var background=new Layer("background",intermediate,finalPass);var clock=new Layer("clock1",new ModelPass(Kind.Float));
        var data=new Data(background,clock);adapter.prepare(data);
        assertSame(intermediate,background.render[0]);assertNotSame(finalPass,background.render[1]);
        assertSame(finalPass.uniforms,background.render[1].uniforms);
        assertEquals(BackgroundShaderTest.SOURCE,finalPass.fs);
        assertTrue(background.render[1].fs.contains(BackgroundShader.UNIFORM));
        assertEquals(BackgroundShaderTest.SOURCE,clock.render[0].fs);
        Object changed=background.render[1];adapter.prepare(data);assertSame(changed,background.render[1]);
    }
    @Test public void intermediateOnlyAndNonClockScenesNeverGetModified() throws Exception {
        var adapter=adapter();var background=new Layer("background",new ModelPass(Kind.FBO));
        var original=background.render[0];adapter.prepare(new Data(background,new Layer("clock1",new ModelPass(Kind.Float))));
        assertSame(original,background.render[0]);
        background=new Layer("background",new ModelPass(Kind.Float));original=background.render[0];
        adapter.prepare(new Data(background,new Layer("preview",new ModelPass(Kind.Float))));assertSame(original,background.render[0]);
    }
    @Test public void stateAndConfigurationWriteOnlyBackgroundUniformAndRestoreAtWake() throws Exception {
        var adapter=adapter();var bg=new Layer("background",new ModelPass(Kind.Float));var clk=new Layer("clock1",new ModelPass(Kind.Float));
        adapter.prepare(new Data(bg,clk));var renderer=new Renderer(bg);var clockRenderer=new Renderer(clk);
        Lifecycle.state=new Scene(true);adapter.update(renderer);adapter.update(clockRenderer);
        assertEquals(List.of(.5f),renderer.pass.values);assertTrue(clockRenderer.pass.values.isEmpty());
        adapter.update(renderer);assertEquals(1,renderer.pass.values.size());
        Lifecycle.state=new Scene(false);adapter.update(renderer);assertEquals(List.of(.5f,0f),renderer.pass.values);
        Lifecycle.state=new Scene(true);configuration=Config.from(Map.of("enabled",true,"dim_enabled",true,"dim",100));
        adapter.refresh();assertEquals(List.of(.5f,0f,1f),renderer.pass.values);assertEquals(1,renderer.engine.draws);
        assertEquals(0,clockRenderer.engine.draws);
        configuration=Config.from(Map.of());adapter.refresh();assertEquals(0f,renderer.pass.values.getLast(),0);
        assertTrue(errors.isEmpty());
    }
    @Test public void missingSceneAndUncompiledPassRetainNativeAndRetryLater() throws Exception {
        var adapter=adapter();var bg=new Layer("background",new ModelPass(Kind.Float));
        adapter.prepare(new Data(bg,new Layer("clock1",new ModelPass(Kind.Float))));
        var renderer=new Renderer(bg);renderer.compiled=false;Lifecycle.state=new Scene(true);
        adapter.update(renderer);assertTrue(renderer.pass.values.isEmpty());renderer.compiled=true;
        adapter.update(renderer);assertEquals(List.of(.5f),renderer.pass.values);
        Lifecycle.state=null;adapter.update(renderer);assertEquals(List.of(.5f,0f),renderer.pass.values);
        int draws=renderer.engine.draws;adapter.forget(renderer);adapter.refresh();assertEquals(draws,renderer.engine.draws);
    }
    @Test public void reflectionContractRejectsMissingFirmwareTypes() {
        try {new WallpaperContract(name->{throw new ClassNotFoundException(name);});fail();}
        catch(ReflectiveOperationException expected){assertTrue(expected.getMessage().contains("COEData"));}
    }
    public enum Kind {Float,UseFBO,FBO}
    public record Uniform(Kind type) {public Kind getType(){return type;}}
    public static class ModelPass {
        final String fs;final HashMap<String,Uniform> uniforms;
        ModelPass(Kind kind){this(BackgroundShaderTest.SOURCE,new HashMap<>(Map.of("original",new Uniform(kind))));}
        ModelPass(String fs,HashMap<String,Uniform> uniforms){this.fs=fs;this.uniforms=uniforms;}
        public String getVs(){return "vertex";}public String getFs(){return fs;}public int getOrder(){return 0;}
        public HashMap<String,Uniform> getUniforms(){return uniforms;}public String[] getStatus(){return null;}
        public String getCs(){return null;}public String[] getEffects(){return null;}
        public ModelPass copy(String vs,String fs,int order,HashMap<String,Uniform> uniforms,String[] status,String cs,String[] effects){return new ModelPass(fs,uniforms);}
    }
    public static class Layer {
        final String name;ModelPass[] render;
        Layer(String name,ModelPass...render){this.name=name;this.render=render;}
        public String getName(){return name;}public ModelPass[] getRender(){return render;}
        public void setRender(ModelPass[] render){this.render=render;}
    }
    public record Data(Layer[] layers){Data(Layer first,Layer second){this(new Layer[]{first,second});}public Layer[] getLayers(){return layers;}}
    public static class Coe {protected void load(Data data,boolean start,boolean cache){}}
    public static class Renderer {
        final Layer layer;final Pass pass=new Pass();final Engine engine=new Engine();boolean compiled=true;
        Renderer(Layer layer){this.layer=layer;}public void onUpdate(double frame){}public void onDestroy(){}
        public Layer getLayer(){return layer;}public Pass getPass(int index){return compiled?pass:null;}public Engine getEngine(){return engine;}
    }
    public static class CoeRenderer extends Renderer {
        public CoeRenderer(Engine engine,float width,float height,Object listener,Data data,int index,boolean cache,Object link){super(data.layers()[index]);}
    }
    public static class Pass {final List<Float> values=new ArrayList<>();public void setParameter(String name,Object...args){assertEquals(BackgroundShader.UNIFORM,name);values.add((Float)args[0]);}}
    public static class Engine {int draws;public long post(Runnable callback){callback.run();return 1L;}public void postDraw(){draws++;}}
    public record Scene(boolean value){public boolean isPanoramicAod(){return value;}}
    public static class Lifecycle {static Scene state=new Scene(false);}
}
