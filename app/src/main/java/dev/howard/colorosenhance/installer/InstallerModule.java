package dev.howard.colorosenhance.installer;

import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ResolveInfo;
import android.content.pm.ActivityInfo;
import android.app.ActivityOptions;
import android.util.Log;
import io.github.libxposed.api.XposedModule;
import java.lang.reflect.Method;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** ColorOS 16/17 native resolution hook. No Activity launch, grant or installation is performed here. */
public final class InstallerModule extends XposedModule {
    private final Set<String> warnings = ConcurrentHashMap.newKeySet();
    private boolean systemServer, initialized;
    private record Configuration(boolean enabled,boolean skip,String target) {}
    private volatile Configuration configuration=new Configuration(false,false,"");
    private SharedPreferences preferences;
    private SharedPreferences.OnSharedPreferenceChangeListener listener;

    @Override public void onModuleLoaded(ModuleLoadedParam param) {
        if (!dev.howard.colorosenhance.migration.PackageActivation.allowed(() -> getRemotePreferences("integration"))) return; systemServer = param.isSystemServer(); }

    @Override public void onSystemServerStarting(SystemServerStartingParam param) {
        if (!systemServer || initialized) return;
        initialized = true;
        try {
            ClassLoader loader = param.getClassLoader();
            Class<?> supervisor = Class.forName("com.android.server.wm.ActivityTaskSupervisor",false,loader);
            Class<?> request = Class.forName("com.android.server.wm.ActivityStarter$Request",false,loader);
            Method requestResolve = request.getDeclaredMethod("resolveActivity",supervisor);
            Method resolve = supervisor.getDeclaredMethod("resolveIntent",Intent.class,String.class,
                    int.class,int.class,int.class,int.class);
            InstallerRequestScope scope = new InstallerRequestScope(request);
            InstallerLaunchScope launchScope = new InstallerLaunchScope();
            // Its compiled body may contain an inlined supervisor.resolveIntent call.
            if (!deoptimize(requestResolve)) throw new IllegalStateException("Request resolution deoptimization unavailable");
            preferences = getRemotePreferences("installer");
            listener = (prefs,key) -> reload();
            preferences.registerOnSharedPreferenceChangeListener(listener);
            reload();
            // Install the inner hook first. It remains inert if the outer hook cannot be installed.
            hook(resolve).setExceptionMode(ExceptionMode.PASSTHROUGH).intercept(chain -> {
                Object original = chain.proceed(); // Native failures propagate unchanged and are never retried.
                Configuration config=configuration;
                if (!config.enabled()) return original;
                try {
                    Intent intent = (Intent)chain.getArg(0);
                    int user = (int)chain.getArg(2);
                    InstallerRouting.Origin origin = scope.originFor(intent,user,(int)chain.getArg(3),(int)chain.getArg(4));
                    if (origin == null) return original;
                    ResolveInfo routed=InstallerRouting.route(config.enabled(),config.target(),intent,(String)chain.getArg(1),user,origin,(ResolveInfo)original,
                            new InstallerRouting.Resolver() {
                                public ResolveInfo resolve(Intent candidate) throws Throwable {
                                    Object[] args = chain.getArgs().toArray(); args[0] = candidate;
                                    return (ResolveInfo)chain.proceed(args);
                                }
                                public java.util.List<ResolveInfo> query(Intent candidate) throws ReflectiveOperationException {
                                    return InstallerHandlerQuery.query(chain.getThisObject(),candidate,(String)chain.getArg(1),user);
                                }
                            });
                    if(routed!=original) launchScope.mark(intent,origin,user);
                    return routed;
                } catch (Throwable failure) { warn("resolution",failure); return original; }
            });
            hook(requestResolve).setExceptionMode(ExceptionMode.PASSTHROUGH).intercept(chain -> {
                if (!configuration.enabled()) return chain.proceed();
                return scope.within(chain.getThisObject(),chain::proceed);
            });
            installConfirmationHook(loader,launchScope);
            log(Log.INFO,"ColorOSEnhance","Installer routing hook loaded (default off; configuration controls routing)");
        } catch (Throwable failure) { configuration=new Configuration(false,false,""); warn("initialization",failure); }
    }
    private void installConfirmationHook(ClassLoader loader,InstallerLaunchScope scope) {
        try {
            Class<?> starter=Class.forName("com.android.server.wm.ActivityStarter",false,loader);
            Class<?> record=Class.forName("com.android.server.wm.ActivityRecord",false,loader);
            Class<?> manager=Class.forName("com.android.server.wm.OplusAppStartConfirmManager",false,loader);
            Method execute=starter.getDeclaredMethod("execute");
            Method confirm=manager.getDeclaredMethod("getCheckConformIntent",String.class,ActivityInfo.class,
                    Intent.class,int.class,int.class,record,int.class,int.class,ActivityOptions.class);
            Method check=manager.getDeclaredMethod("checkStartActivityForConfirm",record,ActivityInfo.class,
                    Intent.class,int.class,int.class,String.class,ActivityOptions.class,
                    Class.forName("android.app.ProfilerInfo",false,loader),boolean.class);
            if(!deoptimize(check)) throw new IllegalStateException("Confirmation deoptimization unavailable");
            hook(confirm).setExceptionMode(ExceptionMode.PASSTHROUGH).intercept(chain->{
                Configuration config=configuration;
                try {
                    if(config.enabled() && config.skip() && InstallerLaunchScope.ordinaryConfirmation((int)chain.getArg(7))
                            && scope.matches((Intent)chain.getArg(2),(ActivityInfo)chain.getArg(1),
                                (int)chain.getArg(3),(String)chain.getArg(0),(int)chain.getArg(6),config.target())) {
                        if(warnings.add("confirmation applied"))log(Log.INFO,"ColorOSEnhance","Installer APK opening confirmation skipped");
                        return null; // Earlier malicious-app/blocked-type checks still run natively.
                    }
                } catch(Throwable failure) {warn("confirmation request",failure);}
                return chain.proceed();
            });
            hook(execute).setExceptionMode(ExceptionMode.PASSTHROUGH).intercept(chain->{
                Configuration config=configuration;
                return config.enabled() && config.skip() ? scope.within(chain::proceed) : chain.proceed();
            });
            log(Log.INFO,"ColorOSEnhance","Installer confirmation hook loaded (request-scoped)");
        } catch(Throwable failure) {warn("confirmation initialization",failure);}
    }
    private void reload() {
        try {
            var values=preferences.getAll();
            Object selected=values.get("target_package");
            String target=selected instanceof String text ? text : values.containsKey("enabled") ? InstallerPreferences.LEGACY_TARGET : "";
            configuration=new Configuration(Boolean.TRUE.equals(values.get("enabled")),
                    Boolean.TRUE.equals(values.get("skip_confirmation")),InstallerPreferences.validPackage(target)?target:"");
        } catch (RuntimeException failure) { configuration=new Configuration(false,false,""); warn("configuration",failure); }
    }
    private void warn(String operation,Throwable failure) {
        if (warnings.add(operation)) log(Log.WARN,"ColorOSEnhance",
                "Installer routing "+operation+" unavailable ("+failure.getClass().getSimpleName()+")");
    }
}
