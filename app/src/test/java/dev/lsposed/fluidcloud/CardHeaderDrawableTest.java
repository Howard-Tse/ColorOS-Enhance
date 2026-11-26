package dev.lsposed.fluidcloud;

import android.graphics.Canvas;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import java.util.function.Supplier;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class) @Config(sdk=36)
public class CardHeaderDrawableTest {
    @Test public void cameraHeaderResizesBothCurrentSdkRenderersAndRestoresTheirNativeHeight() throws Exception {
        var ule = new s7.f0();
        var remoteViews = new p6.c();
        assertSame(s7.f0.class, NativeCardMaterial.sdkLightType(ule.getClass().getClassLoader()));
        assertExtendedSdkMaterial(ule, ule::shaderSize);
        assertExtendedSdkMaterial(remoteViews, remoteViews::shaderSize);
    }

    private static void assertExtendedSdkMaterial(Drawable material, Supplier<y9.l> shaderSize) {
        var wrapper = new CardHeaderDrawable(material, 138, new NativeCardMaterial());
        var canvas = new Canvas();
        wrapper.setBounds(0, 0, 1159, 307);
        wrapper.draw(canvas);
        assertEquals(new Rect(0, 0, 1159, 445), material.getBounds());
        assertEquals(new y9.l(1159f, 445f), shaderSize.get());

        wrapper.release();
        assertEquals(new Rect(0, 0, 1159, 307), material.getBounds());
        assertEquals(new y9.l(1159f, 307f), shaderSize.get());
    }

    @Test public void sdkShaderResetDuringContentAnimationIsCorrectedAtActualDraw() throws Exception {
        var contract=NativeCardMaterial.resolve(NativeCardMaterialTest.Stroke.class,NativeCardMaterialTest.Pair.class);
        var materials=new NativeCardMaterial(type->type==NativeCardMaterialTest.Stroke.class?contract:null);
        var stroke=new NativeCardMaterialTest.Stroke();
        var wrapper=new CardHeaderDrawable(stroke,138,materials);wrapper.setBounds(0,0,1159,307);
        wrapper.draw(new Canvas());assertEquals(new Rect(0,0,1159,445),stroke.getBounds());
        assertEquals(new NativeCardMaterial.Size(1159,445),contract.read(stroke));
        stroke.d(1159,330,0,0);stroke.setBounds(0,0,1159,330);wrapper.setBounds(0,0,1159,330);
        wrapper.draw(new Canvas());assertEquals(new Rect(0,0,1159,468),stroke.getBounds());
        assertEquals(new NativeCardMaterial.Size(1159,468),contract.read(stroke));
        int updates=stroke.updates;wrapper.draw(new Canvas());assertEquals(updates,stroke.updates);
        wrapper.release();assertEquals(new Rect(0,0,1159,330),stroke.getBounds());
        assertEquals(new NativeCardMaterial.Size(1159,330),contract.read(stroke));
    }
}
