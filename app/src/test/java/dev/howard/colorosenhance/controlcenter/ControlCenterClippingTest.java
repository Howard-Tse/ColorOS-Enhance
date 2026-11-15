package dev.howard.colorosenhance.controlcenter;

import android.app.Activity;
import android.app.Application;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.View;
import android.widget.FrameLayout;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class) @Config(sdk=36,application=Application.class)
public class ControlCenterClippingTest {
    private ControlCenterStretch stretch(FrameLayout root,View tile)throws Exception {
        var value=new ControlCenterStretch(root,null,ignored->{});
        NativeControlCenter.put(NativeControlCenter.field(ControlCenterStretch.class,"views"),value,List.of(List.of(tile)));
        NativeControlCenter.put(NativeControlCenter.field(ControlCenterStretch.class,"leases"),value,List.of(List.of(new ControlCenterTranslation(tile))));
        NativeControlCenter.call(NativeControlCenter.method(ControlCenterStretch.class,"allowRegionOverflow"),value);
        return value;
    }
    @Test public void returnCleanupPreservesBothDirectionsOfNativePaddingPolicyChange()throws Exception {
        var activity=Robolectric.buildActivity(Activity.class).setup();
        try {
            for(boolean initial:new boolean[]{true,false}){
                var root=new FrameLayout(activity.get());var pager=new FrameLayout(activity.get());var tile=new View(activity.get());root.addView(pager);pager.addView(tile);
                pager.setClipChildren(true);pager.setClipToPadding(initial);var value=stretch(root,tile);
                assertFalse(pager.getClipChildren());assertEquals(initial,pager.getClipToPadding());
                // Native horizontal return ends while the module still owns its regional return.
                pager.setClipToPadding(!initial);value.clear();
                assertTrue(pager.getClipChildren());assertEquals(!initial,pager.getClipToPadding());
            }
        }finally{activity.pause().stop().destroy();}
    }
    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE)
    public void firstRowRemainsVisibleAbovePagerDuringTheNextNativeOpening()throws Exception {
        var activity=Robolectric.buildActivity(Activity.class).setup();
        try {
            var root=new FrameLayout(activity.get());var pager=new FrameLayout(activity.get());var tile=new View(activity.get());
            root.setClipChildren(false);root.setClipToPadding(false);
            var params=new FrameLayout.LayoutParams(100,100);params.leftMargin=20;params.topMargin=60;root.addView(pager,params);pager.addView(tile,new FrameLayout.LayoutParams(60,60));
            var circle=new GradientDrawable();circle.setShape(GradientDrawable.OVAL);circle.setColor(Color.WHITE);tile.setBackground(circle);
            // Mirrors the native lower pager: child clipping off, 1px bottom padding.
            pager.setClipChildren(false);pager.setPadding(0,0,0,1);pager.setClipToPadding(true);tile.setTranslationY(-40);
            root.measure(View.MeasureSpec.makeMeasureSpec(160,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(200,View.MeasureSpec.EXACTLY));root.layout(0,0,160,200);
            var bitmap=Bitmap.createBitmap(160,200,Bitmap.Config.ARGB_8888);root.draw(new Canvas(bitmap));assertEquals(0,Color.alpha(bitmap.getPixel(50,40)));
            var value=stretch(root,tile);pager.setClipToPadding(false);value.clear();
            bitmap.eraseColor(Color.TRANSPARENT);root.draw(new Canvas(bitmap));
            assertEquals(255,Color.alpha(bitmap.getPixel(50,40)));assertEquals(-40,tile.getTranslationY(),.001f);assertFalse(pager.getClipToPadding());
            bitmap.recycle();
        }finally{activity.pause().stop().destroy();}
    }
}
