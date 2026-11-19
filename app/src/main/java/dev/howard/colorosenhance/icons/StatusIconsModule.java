package dev.howard.colorosenhance.icons;

import dev.howard.colorosenhance.diagnostics.DiagnosticPoint;
import dev.howard.colorosenhance.diagnostics.DiagnosticReporter;

import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.*;

/** ColorOS 16/17 use separate HOME and QS native icon instances. */
public final class StatusIconsModule extends XposedModule {
    private volatile StatusIconConfig config = StatusIconConfig.from(Map.of());
    private final Set<Method> hooked = new HashSet<>();
    private final Map<Class<?>, Method> slotMethods = new HashMap<>();
    private final Set<String> warned = new HashSet<>();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final IconVisibility visibility = new IconVisibility(() -> config);
    private final ShadeProgress progress = new ShadeProgress();
    private final ShadeProgress keyguardProgress = new ShadeProgress();
    private SharedPreferences preferences;
    private SharedPreferences.OnSharedPreferenceChangeListener listener;
    private boolean allowed, initialized;
    private int diagnosticFraction = -1;
    private final Map<Object,List<String>> homeBlocks = new WeakHashMap<>();
    private Method setBlocks;
    private HomeIconOrder ordering;
    @Override public void onModuleLoaded(ModuleLoadedParam param) {
        if (!dev.howard.colorosenhance.migration.PackageActivation.allowed(() -> getRemotePreferences("integration"))) return;
        allowed = !param.isSystemServer() && "com.android.systemui".equals(param.getProcessName());
    }
    @Override public void onPackageReady(PackageReadyParam param) {
        if (!allowed || initialized || !param.isFirstPackage() || !"com.android.systemui".equals(param.getPackageName())) return;
        initialized = true;
        try {
            preferences = getRemotePreferences(StatusIconConfig.GROUP);
            listener = (prefs, key) -> reload();
            preferences.registerOnSharedPreferenceChangeListener(listener);
            reload();
        } catch (RuntimeException unavailable) { warn("配置通道", unavailable); DiagnosticReporter.report(DiagnosticPoint.ICONS, DiagnosticPoint.State.UNSUPPORTED); return; }
        ClassLoader loader = param.getClassLoader();
        installOrdering(loader);
        installSeamless(loader);
        installKeyguardFraction(loader);
        installSlots(loader);
        installHomeOverrides(loader);
        installPanelClock(loader);
        installBluetooth(loader);
        installSpecials(loader);
        synchronized (warned) { DiagnosticReporter.report(DiagnosticPoint.ICONS, warned.isEmpty() ? DiagnosticPoint.State.INSTALLED : DiagnosticPoint.State.PARTIAL); }
    }
    private void reload() {
        StatusIconConfig previous=config;
        try { config = StatusIconConfig.from(preferences.getAll()); }
        catch (RuntimeException unavailable) { config = StatusIconConfig.from(Map.of()); warn("配置读取", unavailable); }
        main.post(()->{visibility.refresh();if(ordering!=null)ordering.refresh();});
        if(previous.enabled()!=config.enabled() || previous.forceHdHome()!=config.forceHdHome()) main.post(this::refreshHomeOverrides);
    }
    private void installOrdering(ClassLoader loader){
        try{
            Class<?> container=Class.forName("com.android.systemui.statusbar.phone.StatusIconContainer",false,loader);
            Class<?> state=Class.forName("com.android.systemui.statusbar.phone.StatusIconContainer$StatusIconState",false,loader);
            Method update=IconReflection.method(container,"updateStates");
            Method apply=IconReflection.method(state,"applyToView",View.class);
            Method setX=IconReflection.method(state,"setXTranslation",float.class);
            Method setAlpha=IconReflection.method(state,"setAlpha",float.class,String.class);
            var visible=state.getDeclaredField("visibleState");visible.setAccessible(true);
            var adapter=new HomeIconOrder(()->config,update,error->warn("自定义图标排序",error));
            hook(apply).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain->{
                if(config.ordersHome()&&chain.getArg(0) instanceof View view){
                    try{
                        var target=adapter.target(view);
                        if(target!=null){
                            visible.setInt(chain.getThisObject(),target.visible()?0:2);
                            if(target.visible()){
                                setX.invoke(chain.getThisObject(),target.x());
                                setAlpha.invoke(chain.getThisObject(),1f,"ColorOS Enhance icon order");
                            }
                        }
                    }catch(ReflectiveOperationException|RuntimeException error){warn("排序动画状态",error);}
                }
                return chain.proceed();
            });
            deoptimize(update);ordering=adapter;visibility.ordering(adapter);
            log(Log.INFO,"StatusIcons","保留原生索引的左右排序接口已加载");
        }catch(ReflectiveOperationException|RuntimeException error){warn("左右图标排序接口",error);}
    }
    private void installHomeOverrides(ClassLoader loader) {
        try {
            Class<?> manager=Class.forName("com.android.systemui.statusbar.phone.ui.IconManager",false,loader);
            setBlocks=IconReflection.method(manager,"setBlockList",List.class);
            hook(setBlocks).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain->{
                Object managerObject=chain.getThisObject();
                String location=String.valueOf(IconReflection.field(managerObject,"mLocation"));
                if(!"HOME".equals(location))return chain.proceed();
                Object input=chain.getArg(0);
                if(!(input instanceof List<?> list) || list.stream().anyMatch(item->!(item instanceof String)))return chain.proceed();
                List<String> original=list.stream().map(String.class::cast).toList();
                homeBlocks.put(managerObject,original);
                return chain.proceed(new Object[]{HomeIconPolicy.effective(original,location,config)});
            });
            log(Log.INFO,"StatusIcons","普通状态栏高清通话策略接口已加载");
        }catch(ReflectiveOperationException | RuntimeException failure){warn("高清通话默认限制",failure);}
    }
    private void refreshHomeOverrides() {
        if(setBlocks==null)return;
        for(var entry:new ArrayList<>(homeBlocks.entrySet())) {
            try {setBlocks.invoke(entry.getKey(),entry.getValue());}
            catch(ReflectiveOperationException | RuntimeException failure){warn("高清通话刷新",failure);}
        }
    }
    private void installPanelClock(ClassLoader loader) {
        try {
            Class<?> header=Class.forName("com.oplus.systemui.separate.OplusQSSimpleHeader",false,loader);
            hook(IconReflection.method(header,"onInit")).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain->{
                Object result=chain.proceed();
                try {
                    Object owner=chain.getThisObject();
                    View clock=(View)IconReflection.call(owner,"getClockView");
                    visibility.panelClock(clock);
                    log(Log.INFO,"StatusIcons","通知中心时钟过渡接口已加载");
                }catch(ReflectiveOperationException | RuntimeException failure){warn("通知中心时钟",failure);}
                return result;
            });
        }catch(ReflectiveOperationException | RuntimeException failure){warn("通知中心时钟接口",failure);}
    }
    private void installSlots(ClassLoader loader) {
        try {
            Class<?> manager = Class.forName("com.android.systemui.statusbar.phone.ui.IconManager", false, loader);
            Class<?> container = Class.forName("com.android.systemui.statusbar.phone.StatusIconContainer", false, loader);
            Method add = Arrays.stream(manager.getDeclaredMethods()).filter(m -> m.getName().equals("addHolder")
                    && m.getParameterCount() == 4 && m.getParameterTypes()[1] == String.class).findFirst().orElseThrow();
            hook(add).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
                Object result = chain.proceed();
                try {
                    ViewGroup group = (ViewGroup) IconReflection.field(chain.getThisObject(), "mGroup");
                    Object location = IconReflection.field(chain.getThisObject(), "mLocation");
                    NativeIconPolicy policy=NativeIconPolicy.read(chain.getThisObject(),group);
                    visibility.group(group, String.valueOf(location),policy);
                    if(ordering!=null&&"HOME".equals(String.valueOf(location)))ordering.group(group,policy);
                    if(Set.of("QS","SHADE_CARRIER_GROUP","KEYGUARD").contains(String.valueOf(location)))
                        registerPanelBattery(group,String.valueOf(location));
                    if(!policy.known())warn("系统图标隐藏策略",new IllegalStateException("Unknown native hide collections"));
                    if (result instanceof View view) installVisibleMethod(view);
                } catch (ReflectiveOperationException | RuntimeException unavailable) { warn("图标容器", unavailable); }
                return result;
            });
            // Native callers can inline isIconVisible; make these two layout decisions observable.
            deoptimize(IconReflection.method(container, "onMeasure", int.class, int.class));
            deoptimize(IconReflection.method(container, "updateStates"));
            log(Log.INFO, "StatusIcons", "原生图标分组接口已加载");
        } catch (ReflectiveOperationException | RuntimeException unavailable) { warn("图标接口", unavailable); }
    }
    private void installVisibleMethod(View view) throws ReflectiveOperationException {
        Method visible = IconReflection.method(view.getClass(), "isIconVisible");
        Method slot = IconReflection.method(view.getClass(), "getSlot");
        Method blocked = IconReflection.method(view.getClass(), "isIconBlocked");
        if (visible.getReturnType() != boolean.class || slot.getReturnType() != String.class) return;
        visibility.icon(view,(String)slot.invoke(view),visible,blocked);
        slotMethods.put(view.getClass(), slot);
        if (!hooked.add(visible)) return;
        hook(visible).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
            Object result = chain.proceed();
            if (!config.enabled() || (config.bluetoothMode() == 0 && config.shadeOnly().isEmpty() && !config.keyguardFollow())
                    || !Boolean.TRUE.equals(result) || !(chain.getThisObject() instanceof View icon)) return result;
            try {
                // Different subclasses can share isIconVisible but override getSlot.
                Method getter = slotMethods.get(icon.getClass());
                if (getter == null) return result;
                String name = (String) getter.invoke(icon);
                return visibility.visible(icon, name, true);
            } catch (ReflectiveOperationException | RuntimeException unavailable) { warn("图标可见性", unavailable); return result; }
        });
    }
    private void installSeamless(ClassLoader loader) {
        var installed=new ArrayList<XposedInterface.HookHandle>();
        try {
            Class<?> fraction=Class.forName("com.oplus.systemui.panelanimation.OplusPanelAnimationExImpl$PanelExpandedFraction",false,loader);
            Class<?> callback=Class.forName("com.android.systemui.panelanimation.PanelAnimationEx$ExpandedFractionChangeListener",false,loader);
            Method add=IconReflection.method(fraction,"addCallback",callback);
            Method read=IconReflection.method(fraction,"getRawFraction");
            Class<?> separate=Class.forName("com.oplus.systemui.separate.OplusSeparateNotificationAndQSExImpl",false,loader);
            Method controls=IconReflection.method(separate,"updateQSExpandFraction",float.class,float.class,float.class,boolean.class);
            Method dispatch=ViewGroup.class.getDeclaredMethod("dispatchDraw",Canvas.class);
            Method draw=ViewGroup.class.getDeclaredMethod("drawChild",Canvas.class,View.class,long.class);
            Class<?> fake=Class.forName("com.oplus.systemui.seamless.widget.StatusBarFakeFrame",false,loader);
            Method source=IconReflection.method(fake,"getMView");
            Method bind=Arrays.stream(fake.getDeclaredMethods()).filter(m->m.getName().equals("setFakeView")
                    && m.getParameterCount()==2 && m.getParameterTypes()[0]==View.class).findFirst().orElseThrow();
            List<Method> mirrors=new ArrayList<>();
            for(String name:List.of("StatusBarFakeFrame","NotificationFakeFrame","SystemIconFakeFrame"))
                mirrors.add(IconReflection.method(Class.forName("com.oplus.systemui.seamless.widget."+name,false,loader),"dispatchDraw",Canvas.class));
            if(read.getReturnType()!=float.class || !callback.isInterface())throw new NoSuchMethodException("Native shade fraction");
            for(var constructor:fraction.getDeclaredConstructors()) {
                installed.add(hook(constructor).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain->{
                    Object result=chain.proceed();
                    try {
                        Object listener=Proxy.newProxyInstance(loader,new Class<?>[]{callback},(proxy,method,args)->{
                            if(method.getDeclaringClass()==Object.class)return switch(method.getName()) {
                                case "hashCode"->System.identityHashCode(proxy);
                                case "equals"->args!=null && args.length==1 && proxy==args[0];
                                default->"ColorOSEnhanceIconFraction";
                            };
                            if("onFractionChanged".equals(method.getName()) && args!=null && args.length==1 && args[0] instanceof Float value)
                                updateFraction(value);
                            return null;
                        });
                        add.invoke(chain.getThisObject(),listener);
                        updateFraction((Float)read.invoke(chain.getThisObject()));
                        log(Log.INFO,"StatusIcons","已接入系统下拉进度");
                    }catch(ReflectiveOperationException | RuntimeException failure){visibility.seamless(false);warn("下拉进度注册",failure);}
                    return result;
                }));
            }
            installed.add(hook(controls).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain->{
                Object result=chain.proceed();float value=(Float)chain.getArg(0);
                visibility.keyguardFraction(keyguardProgress.controls(value));
                updateFraction("controls",value,progress.controls(value));return result;
            }));
            installed.add(hook(dispatch).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain->{
                if(Looper.myLooper()!=main.getLooper())return chain.proceed();
                IconVisibility.Frame frame=visibility.begin((ViewGroup)chain.getThisObject());
                try{return chain.proceed();}finally{visibility.end(frame);}
            }));
            installed.add(hook(draw).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain->{
                ViewGroup parent=(ViewGroup)chain.getThisObject();View child=(View)chain.getArg(1);
                var transform=visibility.transform(parent,child);
                // Even at zero alpha, let Android record the child. OEM mirrors reuse that
                // display list after the source status-bar window stops drawing.
                if(transform.dx()==0 && transform.alpha()==1)return chain.proceed();
                Canvas canvas=(Canvas)chain.getArg(0);int save=canvas.save();
                try {
                    canvas.translate(transform.dx(),0);
                    if(transform.alpha()<1) {
                        if(visibility.reveal(child)) {
                            var reveal=IconDrawPlan.reveal(child.getX(),child.getWidth(),visibility.rightAnchored(parent),transform.alpha());
                            canvas.clipRect(reveal.left(),child.getY(),reveal.right(),child.getY()+child.getHeight());
                        }
                        canvas.saveLayerAlpha(null,Math.round(255*transform.alpha()));
                    }
                    return chain.proceed();
                }finally{canvas.restoreToCount(save);}
            }));
            for(Method mirror:mirrors) {
                installed.add(hook(mirror).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain->{
                    View original=(View)source.invoke(chain.getThisObject());
                    var transform=visibility.mirrored(original);
                    if(transform.dx()==0 && transform.alpha()==1)return chain.proceed();
                    Canvas canvas=(Canvas)chain.getArg(0);int save=canvas.save();
                    try {
                        canvas.translate(transform.dx(),0);
                        if(transform.alpha()<1) {
                            boolean right=original.getParent() instanceof ViewGroup parent && visibility.rightAnchored(parent);
                            if(visibility.reveal(original)) {
                                var reveal=IconDrawPlan.reveal(0,original.getWidth(),right,transform.alpha());
                                canvas.clipRect(reveal.left(),0,reveal.right(),original.getHeight());
                            }
                            canvas.saveLayerAlpha(null,Math.round(255*transform.alpha()));
                        }
                        return chain.proceed();
                    }finally{canvas.restoreToCount(save);}
                }));
                deoptimize(mirror);
            }
            installed.add(hook(bind).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain->{
                Object result=chain.proceed();String type=String.valueOf(chain.getArg(1));
                visibility.mirror((View)chain.getThisObject(),chain.getArg(0)!=null &&
                        Set.of("FAKE_CLOCK","FAKE_NOTIFICATION","FAKE_SYSTEM_ICON","KEYGUARD_FAKE_SYSTEM_ICON").contains(type));
                return result;
            }));
            deoptimize(dispatch);
            visibility.seamless(true);
            log(Log.INFO,"StatusIcons","保留原生布局的下拉绘制接口已加载");
        }catch(ReflectiveOperationException | RuntimeException unavailable) {
            for(var handle:installed)handle.unhook();
            visibility.seamless(false);warn("下拉无缝绘制",unavailable);
        }
    }
    private void installKeyguardFraction(ClassLoader loader) {
        try {
            // The normal notification panel fraction is already 1 on the resting lockscreen.
            // This OEM transition instead runs from lockscreen=0 to notification center=1.
            Class<?> transition=Class.forName("com.oplus.systemui.notification.lockscreen.stack.OplusLockscreenShadeTransitionControllerExImpl",false,loader);
            Method set=IconReflection.method(transition,"setRawFraction",float.class);
            hook(set).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain->{
                Object result=chain.proceed();
                visibility.keyguardFraction(keyguardProgress.notification((Float)chain.getArg(0)));return result;
            });
            for(var constructor:transition.getDeclaredConstructors())hook(constructor)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain->{
                        Object result=chain.proceed();
                        try {visibility.keyguardFraction(keyguardProgress.notification((Float)IconReflection.field(chain.getThisObject(),"rawFraction")));}
                        catch(ReflectiveOperationException | RuntimeException failure){warn("锁屏下拉初始进度",failure);}
                        return result;
                    });
            log(Log.INFO,"StatusIcons","锁屏专用下拉进度接口已加载");
        }catch(ReflectiveOperationException | RuntimeException failure){warn("锁屏下拉进度",failure);}
    }
    private void updateFraction(float value) {
        updateFraction("notification",value,progress.notification(value));
    }
    private void updateFraction(String source,float value,float combined) {
        visibility.fraction(combined);
        if(Log.isLoggable("COSIconDraw",Log.DEBUG)) {
            int step=Math.round(combined*10);
            if(step!=diagnosticFraction) {diagnosticFraction=step;Log.d("COSIconDraw",source+" fraction="+value+", combined="+combined);}
        }
    }
    private void installBluetooth(ClassLoader loader) {
        try {
            Class<?> policy = Class.forName("com.oplus.systemui.statusbar.phone.OplusPhoneStatusBarPolicyExImpl", false, loader);
            Method update = IconReflection.method(policy, "updateBluetooth$1");
            hook(update).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
                Object result = chain.proceed();
                var state = BluetoothStateReader.read(chain.getThisObject());
                if (Looper.myLooper() == main.getLooper()) visibility.bluetooth(state);
                else main.post(() -> visibility.bluetooth(state));
                return result;
            });
        } catch (ReflectiveOperationException | RuntimeException unavailable) { warn("蓝牙接口", unavailable); }
    }
    private void installSpecials(ClassLoader loader) {
        try {
            Class<?> bar = Class.forName("com.android.systemui.statusbar.phone.PhoneStatusBarView", false, loader);
            hook(IconReflection.method(bar, "onFinishInflate")).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
                Object result = chain.proceed();
                if (chain.getThisObject() instanceof ViewGroup group) {registerSpecials(group);if(ordering!=null)ordering.root(group);}
                return result;
            });
            // Clock and battery are separate views, not IconManager slots. Only registered
            // descendants of PhoneStatusBarView are affected; header/lockscreen views are untouched.
            hook(View.class.getDeclaredMethod("setVisibility", int.class)).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
                if (Looper.myLooper() != main.getLooper()) return chain.proceed();
                int requested = (Integer) chain.getArg(0);
                int effective = visibility.visibility((View) chain.getThisObject(), requested);
                return effective == requested ? chain.proceed() : chain.proceed(new Object[]{effective});
            });
        } catch (ReflectiveOperationException | RuntimeException unavailable) { warn("时钟与电池接口", unavailable); }
        try {
            Class<?> keyguard=Class.forName("com.android.systemui.statusbar.phone.KeyguardStatusBarView",false,loader);
            hook(IconReflection.method(keyguard,"onFinishInflate")).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain->{
                Object result=chain.proceed();
                if(chain.getThisObject() instanceof ViewGroup group)registerSpecials(group,"KEYGUARD");
                return result;
            });
            log(Log.INFO,"StatusIcons","锁屏状态栏图标接口已加载");
        }catch(ReflectiveOperationException | RuntimeException unavailable){warn("锁屏状态栏接口",unavailable);}
    }
    private void registerSpecials(View view) {
        registerSpecials(view,"HOME");
    }
    private void registerSpecials(View view,String location) {
        String type = view.getClass().getName();
        if (isSubclass(view.getClass(), "com.android.systemui.statusbar.policy.Clock")) visibility.special(view, "clock",location);
        else if (type.endsWith("BatteryMeterView")) visibility.special(view, "battery",location);
        else if (view instanceof ViewGroup group) for (int i = 0; i < group.getChildCount(); i++) registerSpecials(group.getChildAt(i),location);
    }
    private void registerPanelBattery(ViewGroup icons,String location) {
        View current=icons;
        for(int depth=0;depth<4 && current.getParent() instanceof ViewGroup parent;depth++,current=parent) {
            if(registerBatteryDraws(parent,icons,location))return;
        }
    }
    private boolean registerBatteryDraws(View view, ViewGroup icons,String location) {
        if(view.getClass().getName().endsWith("BatteryMeterView")) {visibility.panelBattery(icons,view,location);return true;}
        boolean found=false;
        if(view instanceof ViewGroup group)for(int i=0;i<group.getChildCount();i++)found|=registerBatteryDraws(group.getChildAt(i),icons,location);
        return found;
    }
    private static boolean isSubclass(Class<?> type, String name) {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) if (name.equals(current.getName())) return true;
        return false;
    }
    private void warn(String part, Throwable error) {
        synchronized (warned) { if (!warned.add(part)) return; }
        DiagnosticReporter.report(DiagnosticPoint.ICONS, DiagnosticPoint.State.PARTIAL);
        log(Log.WARN, "StatusIcons", part + "不匹配，保留对应系统处理", error);
    }
}
