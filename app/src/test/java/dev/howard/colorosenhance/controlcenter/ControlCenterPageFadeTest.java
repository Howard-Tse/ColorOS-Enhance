package dev.howard.colorosenhance.controlcenter;

import android.app.Application;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.view.View;
import android.widget.FrameLayout;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class) @Config(sdk=36,application=Application.class,qualifiers="mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class ControlCenterPageFadeTest {
    private final int[] offset={0};
    private final FrameLayout parent=new FrameLayout(RuntimeEnvironment.getApplication());
    private final FrameLayout pager=new FrameLayout(RuntimeEnvironment.getApplication());
    private final ControlCenterPageFade fade=new ControlCenterPageFade();
    private void setup(){
        parent.addView(pager,new FrameLayout.LayoutParams(200,100));parent.layout(0,0,400,100);pager.layout(100,0,300,100);
        parent.setClipChildren(true);pager.setClipToPadding(true);
    }
    private void sync(){fade.sync(List.of(new ControlCenterPageFade.Target(pager,()->offset[0])));}
    private Bitmap draw(){
        Bitmap bitmap=Bitmap.createBitmap(400,100,Bitmap.Config.ARGB_8888);Canvas canvas=new Canvas(bitmap);canvas.translate(100,0);
        int save=fade.begin(pager,canvas);Paint paint=new Paint();paint.setColor(Color.WHITE);canvas.drawRect(-100,0,300,100,paint);fade.end(pager,canvas,save);
        return bitmap;
    }
    @Test public void movingPageKeepsAllNormalPixelsOpaqueAndFadesOnlyOutside(){
        setup();offset[0]=50;sync();
        try{
            Bitmap image=draw();for(int x=100;x<300;x++)assertEquals("normal page x="+x,255,Color.alpha(image.getPixel(x,50)));
            assertEquals(0,Color.alpha(image.getPixel(79,50)));assertEquals(0,Color.alpha(image.getPixel(320,50)));
            int left=Color.alpha(image.getPixel(90,50)),right=Color.alpha(image.getPixel(310,50));
            assertTrue(left>30 && left<220);assertTrue(right>30 && right<220);
            assertTrue(Color.alpha(image.getPixel(98,50))>left);assertTrue(Color.alpha(image.getPixel(302,50))>right);
        }finally{fade.close();}
    }
    @Test public void stationaryPagesHaveNoExteriorPeekAndNoFadingOfTheirEdgeTiles(){
        setup();sync();
        try{
            for(int page:new int[]{0,200,-200}){offset[0]=page;Bitmap image=draw();
                assertEquals(0,Color.alpha(image.getPixel(99,50)));assertEquals(0,Color.alpha(image.getPixel(300,50)));
                assertEquals(255,Color.alpha(image.getPixel(100,50)));assertEquals(255,Color.alpha(image.getPixel(299,50)));}
            offset[0]=5;Bitmap image=draw();assertEquals(0,Color.alpha(image.getPixel(94,50)));assertTrue(Color.alpha(image.getPixel(97,50))>0);
        }finally{fade.close();}
    }
    @Test public void exteriorFadeFitsTheGapInsteadOfPaintingOverAdjacentControls(){
        setup();View adjacent=new View(parent.getContext());parent.addView(adjacent,new FrameLayout.LayoutParams(94,100));adjacent.layout(0,0,94,100);
        offset[0]=80;sync();
        try{Bitmap image=draw();assertEquals(0,Color.alpha(image.getPixel(93,50)));assertTrue(Color.alpha(image.getPixel(97,50))>0);assertEquals(255,Color.alpha(image.getPixel(100,50)));}
        finally{fade.close();}
    }
    @Test public void paddingIsOutsideTheNormalContentAndRemainsRestorable(){
        setup();pager.setPadding(8,3,8,3);offset[0]=-70;sync();
        try{Bitmap image=draw();for(int x=108;x<292;x++)assertEquals(255,Color.alpha(image.getPixel(x,50)));assertTrue(Color.alpha(image.getPixel(102,50))<255);}
        finally{fade.close();}
        assertTrue(pager.getClipToPadding());assertEquals(8,pager.getPaddingLeft());assertEquals(3,pager.getPaddingTop());
    }
    @Test public void sharedParentClippingRestoresOnlyAfterBothPagingRegionsAreReleased(){
        setup();FrameLayout second=new FrameLayout(parent.getContext());parent.addView(second,new FrameLayout.LayoutParams(100,100));second.layout(0,0,90,100);
        var original=pager.getLayoutParams();
        fade.sync(List.of(new ControlCenterPageFade.Target(pager,()->50),new ControlCenterPageFade.Target(second,()->30)));
        assertFalse(parent.getClipChildren());assertFalse(pager.getClipToPadding());assertFalse(second.getClipToPadding());
        fade.sync(List.of(new ControlCenterPageFade.Target(second,()->30)));
        assertFalse(parent.getClipChildren());assertTrue(pager.getClipToPadding());assertSame(original,pager.getLayoutParams());assertSame(parent,pager.getParent());
        fade.close();assertTrue(parent.getClipChildren());assertTrue(second.getClipToPadding());
    }
}
