package dev.howard.colorosenhance.controlcenter;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Shader;
import android.view.View;
import android.view.ViewGroup;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntSupplier;

/** Exterior-only pager fade. Native bounds, page stride and input are unchanged. */
final class ControlCenterPageFade implements AutoCloseable {
    record Target(ViewGroup view,IntSupplier offset){}
    private final Map<ViewGroup,Page> pages=new IdentityHashMap<>();
    private final Map<ViewGroup,Boolean> parents=new IdentityHashMap<>();
    void sync(List<Target> targets){
        var wanted=java.util.Collections.newSetFromMap(new IdentityHashMap<ViewGroup,Boolean>());
        for(Target target:targets)if(target.view().getWidth()>0 && target.view().getHeight()>0 && target.view().getVisibility()==View.VISIBLE){
            ViewGroup view=target.view();wanted.add(view);
            Page page=pages.computeIfAbsent(view,Page::new);page.offset=target.offset();page.update();
        }
        pages.entrySet().removeIf(entry->{if(wanted.contains(entry.getKey()))return false;entry.getValue().close();return true;});
        var needed=java.util.Collections.newSetFromMap(new IdentityHashMap<ViewGroup,Boolean>());
        for(Page page:pages.values())if((page.leftLimit>0 || page.rightLimit>0) && page.view.getParent() instanceof ViewGroup parent)needed.add(parent);
        parents.entrySet().removeIf(entry->{if(needed.contains(entry.getKey()))return false;restoreParent(entry.getKey(),entry.getValue());return true;});
        for(ViewGroup parent:needed){
            if(!parents.containsKey(parent) || parent.getClipChildren())parents.put(parent,parent.getClipChildren());
            if(parent.getClipChildren())parent.setClipChildren(false);
        }
    }
    boolean contains(View view){return pages.containsKey(view);}
    int begin(View view,Canvas canvas){return pages.get(view).begin(canvas);}
    void end(View view,Canvas canvas,int save){try{pages.get(view).end(canvas);}finally{canvas.restoreToCount(save);}}
    @Override public void close(){for(Page page:pages.values())page.close();pages.clear();for(var entry:parents.entrySet())restoreParent(entry.getKey(),entry.getValue());parents.clear();}
    private static void restoreParent(ViewGroup parent,boolean original){if(!parent.getClipChildren())parent.setClipChildren(original);}
    static float motionDistance(int offset,int width){if(width<=0)return 0;int within=Math.floorMod(offset,width);return Math.min(within,width-within);}
    private static final class Page {
        final ViewGroup view;
        final Paint mask=new Paint();
        IntSupplier offset;
        boolean padding;
        float leftLimit,rightLimit,left,right,drawLeft,drawRight;
        float cachedLeft=Float.NaN,cachedRight=Float.NaN,cachedStart,cachedEnd;
        Page(ViewGroup view){this.view=view;padding=view.getClipToPadding();mask.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_IN));}
        void update(){
            // Preserve later native writes when taking the clipping lease again.
            if(view.getClipToPadding()){padding=true;view.setClipToPadding(false);}
            float limit=20*view.getResources().getDisplayMetrics().density;
            leftLimit=rightLimit=limit;
            if(view.getParent() instanceof ViewGroup parent){
                float start=view.getLeft()+view.getPaddingLeft(),end=view.getRight()-view.getPaddingRight();
                leftLimit=Math.min(limit,Math.max(0,start-(parent.getClipToPadding()?parent.getPaddingLeft():0)));
                rightLimit=Math.min(limit,Math.max(0,parent.getWidth()-(parent.getClipToPadding()?parent.getPaddingRight():0)-end));
                for(int i=0;i<parent.getChildCount();i++){
                    View other=parent.getChildAt(i);if(other==view || other.getVisibility()!=View.VISIBLE || other.getBottom()<=view.getTop() || other.getTop()>=view.getBottom())continue;
                    if(other.getRight()<=start)leftLimit=Math.min(leftLimit,start-other.getRight());
                    if(other.getLeft()>=end)rightLimit=Math.min(rightLimit,other.getLeft()-end);
                }
            }else leftLimit=rightLimit=0;
        }
        int begin(Canvas canvas){
            float travel=motionDistance(offset.getAsInt(),view.getWidth());
            left=Math.min(leftLimit,travel);right=Math.min(rightLimit,travel);
            if(left==0 && right==0){int save=canvas.save();
                canvas.clipRect(padding?view.getPaddingLeft():0,padding?view.getPaddingTop():0,
                        view.getWidth()-(padding?view.getPaddingRight():0),view.getHeight()-(padding?view.getPaddingBottom():0));return save;}
            float start=view.getPaddingLeft(),end=view.getWidth()-view.getPaddingRight();
            drawLeft=start-left;drawRight=end+right;
            if(left!=cachedLeft || right!=cachedRight || start!=cachedStart || end!=cachedEnd){
                float span=drawRight-drawLeft;
                mask.setShader(new LinearGradient(drawLeft,0,drawRight,0,new int[]{Color.TRANSPARENT,Color.BLACK,Color.BLACK,Color.TRANSPARENT},
                        new float[]{0,left/span,(left+end-start)/span,1},Shader.TileMode.CLAMP));
                cachedLeft=left;cachedRight=right;cachedStart=start;cachedEnd=end;
            }
            int save=canvas.saveLayer(drawLeft,0,drawRight,view.getHeight(),null);
            canvas.clipRect(drawLeft,0,drawRight,view.getHeight());return save;
        }
        void end(Canvas canvas){if(left>0 || right>0)canvas.drawRect(drawLeft,0,drawRight,view.getHeight(),mask);}
        void close(){if(!view.getClipToPadding())view.setClipToPadding(padding);view.invalidate();}
    }
}
