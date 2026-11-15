package dev.howard.aodenhance.wallpaper;

import android.content.SharedPreferences;
import android.os.Build;
import android.util.Log;
import dev.howard.aodenhance.Config;
import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;
import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.lang.reflect.Array;

/** ColorOS 17's wallpaper clock requires dimming before foreground compositing. */
public final class WallpaperModule extends XposedModule {
    public static final String PACKAGE="com.heytap.colorfulengine";
    private volatile Config config=Config.from(Map.of());
    private boolean allowed,initialized;
    private SharedPreferences preferences;
    private SharedPreferences.OnSharedPreferenceChangeListener preferenceListener;
    private volatile WallpaperDimmingAdapter adapter;
    private final AtomicBoolean warned=new AtomicBoolean();
    private final AtomicInteger prepareTraces=new AtomicInteger(),updateTraces=new AtomicInteger();

    @Override public void onModuleLoaded(ModuleLoadedParam param) {
        if (!dev.howard.colorosenhance.migration.PackageActivation.allowed(() -> getRemotePreferences("integration"))) return;
        allowed=Build.VERSION.SDK_INT>=37 && !param.isSystemServer() && PACKAGE.equals(param.getProcessName());
    }
    @Override public void onPackageReady(PackageReadyParam param) {
        if(!allowed || initialized || !param.isFirstPackage() || !PACKAGE.equals(param.getPackageName()))return;
        initialized=true;
        var installed=new ArrayList<XposedInterface.HookHandle>();
        try {
            boolean modern;
            try {Class.forName("com.oplus.vfxsdk.naive2.parse.COERenderer",false,param.getClassLoader());modern=true;}
            catch(ClassNotFoundException absent){modern=false;}
            WallpaperContract contract=new WallpaperContract(name->Class.forName(name,false,param.getClassLoader()),modern);
            preferences=getRemotePreferences(Config.GROUP);
            preferenceListener=(prefs,key)->reload();
            preferences.registerOnSharedPreferenceChangeListener(preferenceListener);
            reload();
            adapter=new WallpaperDimmingAdapter(contract,()->config,this::warn);
            Class<?> engineType=Class.forName("com.oplus.vfxsdk.naive2.coe.engine.NativeEngine",false,param.getClassLoader());
            var nativeLoad=engineType.getDeclaredMethod("loadEffect",String.class,Boolean.class);
            nativeLoad.setAccessible(true);
            installed.add(hook(nativeLoad).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain->{
                log(Log.INFO,"AodWallpaper","DIM-ENGINE effect "+chain.getArg(0));
                return chain.proceed();
            }));
            Class<?> coeType=Class.forName("com.oplus.vfxsdk.naive2.COEEngine",false,param.getClassLoader());
            deoptimize(coeType.getDeclaredMethod("d0",String.class,boolean.class,boolean.class));
            // Prepare before any GPU program is created, even if the virtual loader was inlined.
            installed.add(hook(contract.rendererConstructor).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain->{
                try {
                    if(prepareTraces.getAndIncrement()<14) {
                        Object layers=contract.getLayers.invoke(chain.getArg(4));
                        StringBuilder detail=new StringBuilder();
                        for(int i=0;i<Array.getLength(layers);i++) {
                            Object layer=Array.get(layers,i);Object passes=contract.getRender.invoke(layer);
                            detail.append(contract.layerName.invoke(layer)).append(':').append(Array.getLength(passes));
                            if(Array.getLength(passes)>0) {
                                Object pass=Array.get(passes,Array.getLength(passes)-1);String fs=(String)contract.getFs.invoke(pass);
                                detail.append("/patchable=").append(BackgroundShader.patch(fs)!=null).append("/tagged=").append(fs.contains(BackgroundShader.UNIFORM));
                                Object uniforms=contract.passValues[3].invoke(pass);
                                if(uniforms instanceof Map<?,?> values)for(Object value:values.values())detail.append('/').append(contract.uniformType.invoke(value));
                            }
                            detail.append(';');
                        }
                        log(Log.INFO,"AodWallpaper","DIM-ENGINE prepare "+detail);
                    }
                }catch(ReflectiveOperationException|RuntimeException failure){warn(failure);}
                try {adapter.prepare(chain.getArg(4));}catch(ReflectiveOperationException|RuntimeException|LinkageError failure){warn(failure);}
                return chain.proceed();
            }));
            installed.add(hook(contract.update).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain->{
                Object result=chain.proceed();adapter.update(chain.getThisObject());
                try {
                    Object state=contract.scene.get(null);boolean pano=state!=null && (boolean)contract.panoramic.invoke(state);
                    if(pano && updateTraces.getAndIncrement()<10) {
                        Object renderer=chain.getThisObject(),layer=contract.getLayer.invoke(renderer),passes=contract.getRender.invoke(layer);
                        int count=Array.getLength(passes);
                        log(Log.INFO,"AodWallpaper","DIM-ENGINE update layer="+contract.layerName.invoke(layer)+" pano="+pano+" config="+config.enabled+","+config.dimEnabled+","+config.dim+" tagged="+(count>0 && ((String)contract.getFs.invoke(Array.get(passes,count-1))).contains(BackgroundShader.UNIFORM)));
                    }
                }catch(ReflectiveOperationException|RuntimeException failure){warn(failure);}
                return result;
            }));
            installed.add(hook(contract.destroy).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain->{
                adapter.forget(chain.getThisObject());return chain.proceed();
            }));
            log(Log.INFO,"AodWallpaper","独立背景 RGB 压暗合同已加载；时钟、背景纹理和透明度保持原厂处理");
        } catch(ReflectiveOperationException|RuntimeException|LinkageError failure) {
            for(var handle:installed)handle.unhook();
            adapter=null;warn(failure);
        }
    }
    private void reload() {
        try {config=Config.from(preferences.getAll());}catch(RuntimeException failure){config=Config.from(Map.of());warn(failure);}
        if(adapter!=null)adapter.refresh();
    }
    private void warn(Throwable failure) {
        if(warned.compareAndSet(false,true))log(Log.WARN,"AodWallpaper","背景节点合同未匹配或不可用；未完成的压暗保留系统处理",failure);
    }
}
