package dev.howard.colorosenhance.controlcenter;

import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

public final class ControlCenterModule extends XposedModule {
    private volatile ControlCenterConfig config=ControlCenterConfig.from(Map.of());
    private final Handler main=new Handler(Looper.getMainLooper());
    private final Map<Object,ControlCenterSession> sessions=new IdentityHashMap<>();
    private final ArrayList<XposedInterface.HookHandle> hooks=new ArrayList<>();
    private SharedPreferences preferences;
    private SharedPreferences.OnSharedPreferenceChangeListener listener;
    private boolean allowed,initialized;
    @Override public void onModuleLoaded(ModuleLoadedParam param){
        if(!dev.howard.colorosenhance.migration.PackageActivation.allowed(()->getRemotePreferences("integration")))return;
        allowed=!param.isSystemServer() && "com.android.systemui".equals(param.getProcessName());
    }
    @Override public void onPackageReady(PackageReadyParam param){
        if(!allowed || initialized || !param.isFirstPackage() || !"com.android.systemui".equals(param.getPackageName()))return;
        initialized=true;
        try {
            preferences=getRemotePreferences(ControlCenterConfig.GROUP);listener=(prefs,key)->reload();
            preferences.registerOnSharedPreferenceChangeListener(listener);reload();
            NativeControlCenter api=new NativeControlCenter(param.getClassLoader());
            hooks.add(hook(api.attach).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain->{
                Object result=chain.proceed();safe(()->{
                    Object owner=chain.getThisObject();ControlCenterSession previous=sessions.remove(owner);if(previous!=null)previous.close();
                    sessions.put(owner,new ControlCenterSession(api,owner,()->config,this::warn,message->log(Log.INFO,"ControlCenter",message)));
                });return result;
            }));
            hooks.add(hook(api.detach).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain->{
                safe(()->{ControlCenterSession session=sessions.remove(chain.getThisObject());if(session!=null)session.close();});return chain.proceed();
            }));
            hooks.add(hook(api.enterEdit).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain->{
                safe(()->{var session=sessions.get(chain.getThisObject());if(session!=null)session.suspend();});return chain.proceed();
            }));
            hooks.add(hook(api.exitEdit).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain->{
                Object result=chain.proceed();safe(()->{var session=sessions.get(chain.getThisObject());if(session!=null)session.resume();});return result;
            }));
            hooks.add(hook(api.panelExpanded).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain->{
                Object result=chain.proceed();safe(()->{var session=sessions.get(chain.getThisObject());if(session!=null)session.expandedChanged();});return result;
            }));
            if(api.seamless!=null){
                hooks.add(hook(api.seamless.fraction).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain->{
                    Object result=chain.proceed();safe(()->{for(var session:sessions.values())session.iconFraction(chain.getThisObject(),(float)chain.getArg(0));});return result;
                }));deoptimize(api.seamless.fraction);
            }
            hooks.add(hook(api.dispatch).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain->{
                var session=forRoot((View)chain.getThisObject());MotionEvent event=(MotionEvent)chain.getArg(0);
                if(session!=null)safe(()->session.observe(event));
                try{return chain.proceed();}finally{if(session!=null)safe(()->session.finishGesture(event));}
            }));
            hooks.add(hook(api.intercept).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain->{
                var session=forRoot((View)chain.getThisObject());
                if(session!=null && session.keepGesture((MotionEvent)chain.getArg(0)))return true;
                return chain.proceed();
            }));
            hooks.add(hook(api.touch).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain->{
                var session=forRoot((View)chain.getThisObject());
                if(session!=null && session.ownsTouch())return session.scrollTouch((MotionEvent)chain.getArg(0));
                return chain.proceed();
            }));
            // Leave dispatchTouchEvent intact: tile clicks, long presses and
            // embedded controls still receive their ordinary touch stream.
            // Only the pager's own interception/scroll handling is disabled.
            if(api.tileIntercept!=null && api.tileTouch!=null)for(var method:List.of(api.tileIntercept,api.tileTouch)){
                hooks.add(hook(method).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain->{
                    View view=(View)chain.getThisObject();
                    for(var session:sessions.values())if(session.blocksTileScrolling(view))return false;
                    return chain.proceed();
                }));deoptimize(method);
            }
            if(api.pagingArea!=null){
                hooks.add(hook(api.pagingArea).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain->{
                    var session=sessions.get(chain.getThisObject());
                    // The outer native panel uses this area to exclude pager
                    // touches from its horizontal edge rebound. A flattened
                    // grid is no longer a pager; keep the native panel gesture.
                    if(session!=null && session.verticalTiles())return new android.graphics.Rect();
                    return chain.proceed();
                }));deoptimize(api.pagingArea);
            }
            hooks.add(hook(api.subSize).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain->{
                int result=(int)chain.proceed();Object manager=NativeControlCenter.get(api.pullManager,chain.getThisObject());
                for(var session:sessions.values())result=session.animationRows(manager,result);
                return result;
            }));
            hooks.add(hook(api.setLocations).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain->{
                Object result=chain.proceed();safe(()->{for(var session:sessions.values())session.nativeLocations(chain.getThisObject(),(List<?>)chain.getArg(0));});return result;
            }));
            hooks.add(hook(api.gridNum).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain->{
                Object result=chain.proceed();safe(()->{for(var session:sessions.values())session.nativeGrid(chain.getThisObject(),(int)chain.getArg(0));});return result;
            }));
            hooks.add(hook(api.refreshPages).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain->{
                Object result=chain.proceed();safe(()->{for(var session:sessions.values())session.nativePages(chain.getThisObject());});return result;
            }));
            hooks.add(hook(api.draw).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain->{
                View view=(View)chain.getThisObject();ControlCenterSession owner=null;
                for(var session:sessions.values())if(session.fade(view)){owner=session;break;}
                if(owner==null)return chain.proceed();
                Canvas canvas=(Canvas)chain.getArg(0);int save=owner.beginFade(view,canvas);
                try{return chain.proceed();}finally{owner.endFade(view,canvas,save);}
            }));
            deoptimize(api.attach);deoptimize(api.dispatch);deoptimize(api.intercept);deoptimize(api.touch);deoptimize(api.subSize);deoptimize(api.setLocations);deoptimize(api.gridNum);deoptimize(api.refreshPages);
            log(Log.INFO,"ControlCenter","Native grid/scroll contracts installed; seamless scroll handoff="+(api.seamless!=null)+"; optional controls default off");
        }catch(ReflectiveOperationException|RuntimeException|LinkageError error){
            for(var handle:hooks)handle.unhook();hooks.clear();for(var session:sessions.values())session.close();sessions.clear();warn(error);
        }
    }
    private ControlCenterSession forRoot(View root){for(var session:sessions.values())if(session.root==root)return session;return null;}
    private void reload(){ControlCenterConfig previous=config;try{config=ControlCenterConfig.from(preferences.getAll());}catch(RuntimeException error){config=ControlCenterConfig.from(Map.of());warn(error);}
        if(!previous.equals(config))log(Log.INFO,"ControlCenter","Configuration changed: "+config);
        main.post(()->{for(var session:sessions.values())session.settingsChanged();});}
    private void safe(Runnable action){try{action.run();}catch(RuntimeException|LinkageError error){warn(error);}}
    private void warn(Throwable error){log(Log.ERROR,"ControlCenter","Control-center adapter unavailable; native behavior retained",error);}
}
