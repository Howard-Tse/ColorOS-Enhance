package dev.howard.colorosenhance.controlcenter;

import android.content.res.Configuration;
import android.graphics.Canvas;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.FrameLayout;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;
import static dev.howard.colorosenhance.controlcenter.NativeControlCenter.*;

final class ControlCenterSession {
    final NativeControlCenter api;
    final Object component;
    final FrameLayout root;
    private final ViewGroup panel,tiles;
    private final View indicator,leftTiles;
    private final ControlCenterPanel panelHeight;
    private final ControlCenterFooter footer;
    private final ControlCenterTransition transition;
    private final Supplier<ControlCenterConfig> config;
    private final Consumer<Throwable> failed;
    private final Consumer<String> report;
    private final ViewTreeObserver.OnGlobalLayoutListener changed=this::schedule;
    private final View.OnLayoutChangeListener resized=this::layoutChanged;
    private final Runnable refresh=this::applySafely;
    private Object layout,layoutSettings;
    private List<?> originalLocs=List.of(),appliedLocs=List.of();
    private int originalRows,appliedRows,originalPage;
    private ControlCenterScroller scroller;
    private ControlCenterIconMotion iconMotion;
    private boolean iconMotionFailed;
    private boolean queued,closed,suspended,faulted,eligibleGesture;
    boolean applying;
    private final ControlCenterPageFade pageFade=new ControlCenterPageFade();
    private List<?> lastSource=List.of();
    private Object[] lastItems={};
    private int lastRows,lastColumns,lastCell,lastWidth,lastHeight;

    ControlCenterSession(NativeControlCenter api,Object component,Supplier<ControlCenterConfig> config,Consumer<Throwable> failed,Consumer<String> report) {
        this.api=api;this.component=component;this.config=config;this.failed=failed;this.report=report;
        root=(FrameLayout)get(api.componentView,component);panel=(ViewGroup)get(api.panel,component);
        tiles=(ViewGroup)get(api.tiles,component);leftTiles=(View)get(api.leftTiles,component);indicator=(View)get(api.indicator,component);
        panelHeight=new ControlCenterPanel(panel);
        footer=new ControlCenterFooter((View)get(api.bottomArea,component));
        transition=new ControlCenterTransition(component);
        root.getViewTreeObserver().addOnGlobalLayoutListener(changed);root.addOnLayoutChangeListener(resized);schedule();
    }
    boolean wide(){return root.getResources().getConfiguration().orientation==Configuration.ORIENTATION_LANDSCAPE;}
    boolean editing(){return suspended || Boolean.TRUE.equals(get(api.editing,component)) || Boolean.TRUE.equals(get(api.adding,component));}
    void schedule(){if(iconMotion!=null)iconMotion.schedule();if(!closed&&!queued){queued=true;root.postOnAnimation(refresh);}}
    private void layoutChanged(View view,int l,int t,int r,int b,int oldL,int oldT,int oldR,int oldB){
        if(r-l!=oldR-oldL || b-t!=oldB-oldT){root.removeCallbacks(refresh);queued=false;schedule();}
    }
    void settingsChanged(){schedule();tiles.invalidate();if(leftTiles!=null)leftTiles.invalidate();}
    private void applySafely(){
        queued=false;if(closed||faulted)return;
        try{apply();}catch(ReflectiveOperationException|RuntimeException|LinkageError error){
            faulted=true;pageFade.close();try{restore();}catch(RuntimeException ignored){}failed.accept(error);
        }
    }
    private void apply()throws ReflectiveOperationException {
        updateFades();
        if(!config.get().vertical(wide()) || editing()){restore();return;}
        if(scroller!=null && root.getVisibility()!=View.VISIBLE)scroller.reset();
        if(root.getWidth()==0 || root.getHeight()==0)return;
        Object current=call(api.manager,tiles);
        if(!api.layoutType.isInstance(current))throw new IllegalStateException("Unsupported native tile layout manager");
        if(layout!=current){restore();layout=current;layoutSettings=call(api.layoutConfig,layout);originalPage=(int)call(api.pageIndex,layout);}
        int nativeRows=(int)get(api.rows,layoutSettings),columns=(int)get(api.columns,layoutSettings),cell=(int)get(api.cellHeight,layoutSettings);
        List<?> positions=(List<?>)call(api.locations,layoutSettings);
        // Native rowCount stays untouched: data rebinding and drag models use it
        // to generate the original pages. Only presentation Locs are flattened.
        originalRows=nativeRows;
        if(scroller==null)originalPage=(int)call(api.pageIndex,layout);
        if(scroller==null || !positions.equals(appliedLocs))originalLocs=List.copyOf(positions);
        Object adapter=call(api.adapter,tiles);if(adapter==null || originalRows<=0 || columns<=0 || cell<=0)return;
        List<?> items=(List<?>)call(api.data,adapter);if(items.size()!=originalLocs.size())return;
        if(scroller!=null && originalRows==lastRows && columns==lastColumns && cell==lastCell
                && root.getWidth()==lastWidth && root.getHeight()==lastHeight && originalLocs.equals(lastSource)
                && positions.equals(appliedLocs) && sameItems(items)
                && panel.getLayoutParams().height==root.getHeight()+ControlCenterLayout.extraHeight(originalRows,appliedRows,cell)
                && footer.matches(ControlCenterLayout.extraHeight(originalRows,appliedRows,cell))
                && (int)call(api.pageCount,layout)==1 && (indicator==null || indicator.getVisibility()==View.GONE))return;
        List<ControlCenterLayout.Cell> cells=new ArrayList<>();
        for(int i=0;i<items.size();i++){
            Object at=originalLocs.get(i),span=call(api.size,items.get(i));
            cells.add(new ControlCenterLayout.Cell((int)call(api.locPage,at),(int)call(api.locColumn,at),
                    (int)call(api.locRow,at),(int)call(api.colSpan,span),(int)call(api.rowSpan,span)));
        }
        var plan=ControlCenterLayout.flatten(cells,originalRows,columns);
        if(plan==null || plan.rows()>32)throw new IllegalStateException("Unsupported native tile spans/animation row count");
        List<Object> expanded=new ArrayList<>();
        for(var at:plan.positions())expanded.add(api.loc.newInstance(0,at.column(),at.row()));
        int height=root.getHeight()+ControlCenterLayout.extraHeight(originalRows,plan.rows(),cell);
        applying=true;
        try {
            if(scroller==null){
                if(api.tileStopScroll!=null)call(api.tileStopScroll,tiles);
                tiles.scrollTo(0,0);
                scroller=new ControlCenterScroller(root,report,()->config.get().feedback(wide()));
                if(api.seamless!=null && !iconMotionFailed)try{iconMotion=api.seamless.create(component,root);}
                catch(RuntimeException|LinkageError unavailable){iconMotionFailed=true;failed.accept(unavailable);}
            }
            panelHeight.resize(height);
            scroller.resize(height-root.getHeight());
            footer.resize(ControlCenterLayout.extraHeight(originalRows,plan.rows(),cell));
            boolean changedRows=appliedRows!=plan.rows();appliedRows=plan.rows();
            if(!expanded.equals(appliedLocs) || !positions.equals(expanded)){
                appliedLocs=List.copyOf(expanded);call(api.setLocations,layout,expanded);
                call(api.refreshPages,layout,1,false);call(api.scrollPage,layout,0);
            }
            if((int)call(api.pageCount,layout)!=1){call(api.refreshPages,layout,1,false);call(api.scrollPage,layout,0);}
            if(indicator!=null && indicator.getVisibility()!=View.GONE)indicator.setVisibility(View.GONE);
            if(changedRows)call(api.animationRowsChanged,get(api.tileComponent,component),columns,appliedRows);
            lastSource=List.copyOf(originalLocs);lastItems=items.toArray();lastRows=originalRows;lastColumns=columns;lastCell=cell;
            lastWidth=root.getWidth();lastHeight=root.getHeight();
        }finally{applying=false;}
    }
    private boolean sameItems(List<?> items){if(items.size()!=lastItems.length)return false;for(int i=0;i<items.size();i++)if(items.get(i)!=lastItems[i])return false;return true;}
    void nativeLocations(Object manager,List<?> source){
        if(!applying && manager==layout && !source.equals(appliedLocs)){originalLocs=List.copyOf(source);schedule();}
    }
    void nativeGrid(Object settings,int rows){if(!applying && settings==layoutSettings){originalRows=rows;schedule();}}
    void nativePages(Object manager){if(!applying && manager==layout)schedule();}
    void suspend(){suspended=true;pageFade.close();restore();}
    void resume(){suspended=false;schedule();}
    void expandedChanged(){if(scroller!=null && root.getVisibility()!=View.VISIBLE)scroller.reset();if(iconMotion!=null)iconMotion.schedule();}
    void iconFraction(Object owner,float fraction){
        if(iconMotion!=null && api.seamless.owner(component)==owner)iconMotion.fraction(fraction);
    }
    void observe(MotionEvent event){
        if(event.getActionMasked()!=MotionEvent.ACTION_DOWN)return;
        eligibleGesture=!faulted && scroller!=null && !editing() && !Boolean.TRUE.equals(get(api.closing,component)) && transition.ready();
        // Never switch from native expansion to content scrolling mid-gesture.
        if(eligibleGesture)scroller.begin(event);
    }
    boolean keepGesture(MotionEvent event){
        if(!eligibleGesture || scroller==null || Boolean.TRUE.equals(get(api.closing,component))
                || event.getActionMasked()!=MotionEvent.ACTION_DOWN && event.getActionMasked()!=MotionEvent.ACTION_MOVE)return false;
        boolean captured=scroller.captured(),keep=scroller.intercept(event);
        if(keep && !captured)api.contentCaptured(component,root);
        return keep;
    }
    boolean ownsTouch(){return eligibleGesture && scroller!=null && scroller.dragging();}
    boolean verticalTiles(){return !closed && !faulted && scroller!=null && config.get().vertical(wide()) && !editing();}
    boolean blocksTileScrolling(View view){return verticalTiles() && (view==tiles || view==leftTiles);}
    boolean scrollTouch(MotionEvent event){return scroller!=null && scroller.touch(event);}
    int animationRows(Object manager,int fallback){return !closed && !faulted && scroller!=null && appliedRows>0 && !editing() && manager==layout?appliedRows:fallback;}
    void finishGesture(MotionEvent event){
        if(!eligibleGesture || scroller==null || event.getActionMasked()!=MotionEvent.ACTION_UP && event.getActionMasked()!=MotionEvent.ACTION_CANCEL)return;
        boolean interactive=!editing() && !Boolean.TRUE.equals(get(api.closing,component)) && transition.ready();
        scroller.end(event,interactive);if(interactive && scroller.captured())api.finishContentTouch(component);
        eligibleGesture=false;
    }
    private void updateFades(){
        if(closed || faulted || !config.get().fade(wide()) || editing() || api.scrollOffset==null){pageFade.close();return;}
        List<ControlCenterPageFade.Target> targets=new ArrayList<>();
        for(View candidate:new View[]{tiles,leftTiles})if(candidate instanceof ViewGroup group){
            Object manager=call(api.manager,group);
            if(api.layoutType.isInstance(manager))targets.add(new ControlCenterPageFade.Target(group,()->((int[])call(api.scrollOffset,manager))[0]));
        }
        pageFade.sync(targets);
    }
    boolean fade(View view){return !closed && !faulted && config.get().fade(wide()) && !editing() && pageFade.contains(view);}
    int beginFade(View view,Canvas canvas){return pageFade.begin(view,canvas);}
    void endFade(View view,Canvas canvas,int save){pageFade.end(view,canvas,save);}
    private void restore(){
        eligibleGesture=false;
        if(scroller==null)return;
        applying=true;
        try {
            if(layoutSettings!=null && ((List<?>)call(api.locations,layoutSettings)).equals(appliedLocs))call(api.setLocations,layout,originalLocs);
            if(layout!=null){call(api.refreshPages,layout,-1,false);int count=(int)call(api.pageCount,layout);call(api.scrollPage,layout,Math.max(0,Math.min(originalPage,count-1)));}
            if(indicator!=null)indicator.setVisibility(layout!=null && (int)call(api.pageCount,layout)>1?View.VISIBLE:View.GONE);
        }finally{
            footer.restore();panelHeight.restore();scroller.close();scroller=null;
            if(iconMotion!=null){iconMotion.close();iconMotion=null;}
            appliedLocs=List.of();appliedRows=0;lastItems=new Object[0];lastSource=List.of();
            try{if(!closed)call(api.animationRowsChanged,get(api.tileComponent,component),lastColumns,originalRows);}finally{applying=false;}
        }
    }
    void close(){closed=true;root.removeCallbacks(refresh);root.removeOnLayoutChangeListener(resized);if(root.getViewTreeObserver().isAlive())root.getViewTreeObserver().removeOnGlobalLayoutListener(changed);pageFade.close();restore();}
}
