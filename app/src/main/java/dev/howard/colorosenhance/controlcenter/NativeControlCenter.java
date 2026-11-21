package dev.howard.colorosenhance.controlcenter;

import android.graphics.Canvas;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;

/** Capability-checked ColorOS control-center contracts; no firmware-number selection. */
final class NativeControlCenter {
    static final String ROOT="com.oplus.systemui.plugins.qs.OplusQSRootView";
    static final String COMPONENT="com.oplus.systemui.plugins.qs.OplusQSRootViewComponent";
    static final String GRID="com.oplus.systemui.qs.base.widget.recyclerview.StaggeredPagerLayoutManager";
    final Class<?> rootType,layoutType;
    final Method attach,detach,enterEdit,exitEdit,dispatch,intercept,touch,draw,subSize,panelExpanded;
    final Method manager,layoutConfig,locations,setLocations,refreshPages,scrollPage,pageIndex,pageCount;
    final Method adapter,data,size,colSpan,rowSpan,locPage,locColumn,locRow,gridNum,fullyExpanded,resetTouch,animationRowsChanged;
    final Method scrollOffset;
    final Method tileIntercept,tileTouch,tileStopScroll,pagingArea;
    final Constructor<?> loc;
    final Field componentView,panel,tiles,leftTiles,indicator,bottomArea,editing,adding,closing,rows,columns,cellHeight,pullManager,tileComponent;
    final Seamless seamless;
    private final Field tracking,longClick;
    private final Method trackingStopped;
    NativeControlCenter(ClassLoader loader)throws ReflectiveOperationException {
        rootType=Class.forName(ROOT,false,loader);Class<?> component=Class.forName(COMPONENT,false,loader);
        layoutType=Class.forName(GRID,false,loader);Class<?> config=Class.forName(GRID+"$LayoutConfig",false,loader);
        Class<?> recycler=Class.forName("androidx.recyclerview.widget.RecyclerView",false,loader);
        Class<?> parallel=Class.forName("com.oplus.systemui.qs.base.widget.recyclerview.ParallelCOUIRecyclerView",false,loader);
        Class<?> nativeAdapter=Class.forName("com.oplusos.systemui.common.adapter.BaseRecyclerAdapter",false,loader);
        Class<?> gridData=Class.forName("com.oplusos.systemui.common.viewholder.GridData",false,loader);
        Class<?> span=Class.forName("com.oplusos.systemui.common.model.SpanSize",false,loader);
        Class<?> location=Class.forName("com.oplusos.systemui.common.model.Loc",false,loader);
        if(!FrameLayout.class.isAssignableFrom(rootType))throw new NoSuchMethodException("QS root type");
        attach=method(component,"onViewAttached");detach=method(component,"onViewDetached");
        enterEdit=method(component,"enterEditMode",boolean.class);exitEdit=method(component,"exitEditMode",boolean.class);
        dispatch=method(rootType,"dispatchTouchEvent",MotionEvent.class);intercept=method(rootType,"onInterceptTouchEvent",MotionEvent.class);
        touch=method(rootType,"onTouchEvent",MotionEvent.class);
        Method area=null;try{area=method(component,"getQSPageViewArea");}catch(NoSuchMethodException unavailable){}
        pagingArea=area;
        Class<?> pull=Class.forName("com.oplus.systemui.plugins.qs.customize.view.animation.pull.PullAnimationProxy",false,loader);
        subSize=method(pull,"getSubSize");pullManager=field(pull,"customLayoutManager");
        draw=method(parallel,"dispatchDraw",Canvas.class);
        Method pagerIntercept=null,pagerTouch=null;
        try{pagerIntercept=method(parallel,"onInterceptTouchEvent",MotionEvent.class);pagerTouch=method(parallel,"onTouchEvent",MotionEvent.class);}
        catch(NoSuchMethodException unavailable){pagerIntercept=null;pagerTouch=null;}
        tileIntercept=pagerIntercept;tileTouch=pagerTouch;
        Method stopScroll=null;try{stopScroll=method(parallel,"stopScroll");}catch(NoSuchMethodException unavailable){}
        tileStopScroll=stopScroll;
        if(dispatch.getDeclaringClass()!=rootType || intercept.getDeclaringClass()!=rootType || draw.getDeclaringClass()!=parallel)
            throw new NoSuchMethodException("QS drawing/input overrides");
        componentView=field(component,"mView");panel=field(component,"panelContainer");tiles=field(component,"otherTilesContainerView");
        leftTiles=field(component,"otherTilesLandLeftContainerView");indicator=field(component,"pageIndicatorParent");
        bottomArea=field(component,"bottomArea");
        tileComponent=field(component,"qsOtherTilesComponent");
        animationRowsChanged=method(tileComponent.getType(),"onCellNumChanged",int.class,int.class);
        editing=field(component,"isEditMode");adding=field(component,"isAddingMode");closing=field(component,"isClosing");
        fullyExpanded=method(component,"isFullyExpanded");resetTouch=method(component,"resetTouch");
        tracking=optionalField(component,"isTracking");longClick=optionalField(component,"longClickRunnable");
        Method stop=null;
        for(String name:new String[]{"onTrackingStopped$1","onTrackingStopped"})try{stop=method(component,name,boolean.class);break;}catch(NoSuchMethodException ignored){}
        trackingStopped=stop;
        Seamless motion=null;try{motion=new Seamless(component);}catch(ReflectiveOperationException unavailable){}
        seamless=motion;
        panelExpanded=method(component,"updatePanelExpanded",boolean.class);
        manager=method(recycler,"getLayoutManager");adapter=method(recycler,"getAdapter");data=method(nativeAdapter,"getDataList");
        layoutConfig=method(layoutType,"getLayoutConfig");locations=method(config,"getSpecifyLoc");
        rows=field(config,"rowCount");columns=field(config,"colCount");cellHeight=field(config,"cellHeight");
        gridNum=method(config,"setGridNum",int.class,int.class);setLocations=method(layoutType,"setLoc",List.class);
        refreshPages=method(layoutType,"refreshPageCount",int.class,boolean.class);scrollPage=method(layoutType,"scrollToPage",int.class);
        pageIndex=method(layoutType,"getCurrentPageIndex");pageCount=method(layoutType,"getPageCount");
        Method offset=null;try{offset=method(layoutType,"currScrollOffset");}catch(NoSuchMethodException unavailable){}
        scrollOffset=offset;
        size=method(gridData,"getSize");colSpan=method(span,"getColSpan");rowSpan=method(span,"getRowSpan");
        locPage=method(location,"getPageIndex");locColumn=method(location,"getStartCol");locRow=method(location,"getStartRow");
        loc=location.getConstructor(int.class,int.class,int.class);
    }
    void contentCaptured(Object component,View root){if(longClick!=null && get(longClick,component) instanceof Runnable pending)root.removeCallbacks(pending);}
    void finishContentTouch(Object component){
        if(tracking!=null && trackingStopped!=null && Boolean.TRUE.equals(get(tracking,component)))call(trackingStopped,component,false);
        call(resetTouch,component);
    }
    static final class Seamless {
        final Field controller;
        final Method fraction,header,mirror,animator,rawFraction;
        private final Field animatorFacade;
        Seamless(Class<?> component)throws ReflectiveOperationException {
            controller=field(component,"fakeStatusContainerController");Class<?> type=controller.getType();
            fraction=method(type,"updateRightArea",float.class);
            header=method(type,"getStatusBarContainer");mirror=method(type,"getFakeLayout");
            Method manager;
            Field facade;
            try {
                manager=method(component,"getQsPanelAnimatorManager");
                facade=null;
            } catch (NoSuchMethodException older) {
                manager=null;
                facade=field(component,"qsPanelAnimatorFacade");
            }
            animator=manager;animatorFacade=facade;
            rawFraction=method(animator!=null?animator.getReturnType():animatorFacade.getType(),"getRawFraction");
            if(!View.class.isAssignableFrom(header.getReturnType()) || !View.class.isAssignableFrom(mirror.getReturnType()))
                throw new NoSuchMethodException("QS mirror/header type");
            if(rawFraction.getReturnType()!=float.class)throw new NoSuchMethodException("QS raw fraction type");
        }
        Object owner(Object component){return get(controller,component);}
        float progress(Object component){
            Object source=animator!=null?call(animator,component):get(animatorFacade,component);
            return (float)call(rawFraction,source);
        }
        ControlCenterIconMotion create(Object component,View root){
            Object owner=owner(component);
            return new ControlCenterIconMotion(root,(View)call(header,owner),(View)call(mirror,owner),progress(component));
        }
    }
    private static Field optionalField(Class<?> type,String name){try{return field(type,name);}catch(NoSuchFieldException unavailable){return null;}}
    static Field field(Class<?> type,String name)throws NoSuchFieldException {
        for(Class<?> owner=type;owner!=null;owner=owner.getSuperclass())try{Field value=owner.getDeclaredField(name);value.setAccessible(true);return value;}
        catch(NoSuchFieldException missing){}
        throw new NoSuchFieldException(name);
    }
    static Method method(Class<?> type,String name,Class<?>... args)throws NoSuchMethodException {
        for(Class<?> owner=type;owner!=null;owner=owner.getSuperclass())try{Method value=owner.getDeclaredMethod(name,args);value.setAccessible(true);return value;}
        catch(NoSuchMethodException missing){}
        throw new NoSuchMethodException(name);
    }
    static Object get(Field field,Object owner){try{return field.get(owner);}catch(ReflectiveOperationException error){throw new IllegalStateException(error);}}
    static void put(Field field,Object owner,Object value){try{field.set(owner,value);}catch(ReflectiveOperationException error){throw new IllegalStateException(error);}}
    static Object call(Method method,Object owner,Object... args){try{return method.invoke(owner,args);}catch(ReflectiveOperationException error){throw new IllegalStateException(error);}}
}
