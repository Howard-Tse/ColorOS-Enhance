package dev.howard.aodenhance.wallpaper;

import org.junit.Test;
import static org.junit.Assert.*;

public class BackgroundShaderTest {
    static final String SOURCE="#version 300 es\nprecision highp float;\nout vec4 fragColor;\n"
            +"void main() { fragColor=vec4(0.8,0.7,0.6,0.25); }";
    @Test public void wrapsNativeEntryWithoutChangingItsAlphaOrTextureCalculations() {
        String result=BackgroundShader.patch(SOURCE);
        assertNotNull(result);assertTrue(result.contains("void ceNativeBackgroundMain()"));
        assertTrue(result.contains("fragColor=vec4(0.8,0.7,0.6,0.25);"));
        assertTrue(result.contains("fragColor.rgb *= (1.0 - clamp(ceBackgroundDim, 0.0, 1.0));"));
        assertFalse(result.contains("fragColor.a *="));assertNull(BackgroundShader.patch(result));
    }
    @Test public void incompatibleOrAmbiguousShadersAreUnchanged() {
        for(String original:new String[]{null,"",SOURCE.replace("300 es","100"),SOURCE.replace("out vec4 fragColor;",""),
                SOURCE+"\nvoid main(){}",SOURCE+"\nfloat ceBackgroundDim;"})assertNull(BackgroundShader.patch(original));
        assertNotNull(BackgroundShader.patch(SOURCE.replace("main()","main ( void )")));
    }
    @Test public void onlyPanoramicWithBothSwitchesAppliesClampedExtraDim() {
        assertEquals(.5f,BackgroundShader.amount(true,true,50,true),0);
        assertEquals(1f,BackgroundShader.amount(true,true,1000,true),0);
        assertEquals(0f,BackgroundShader.amount(true,true,-20,true),0);
        assertEquals(0f,BackgroundShader.amount(false,true,50,true),0);
        assertEquals(0f,BackgroundShader.amount(true,false,50,true),0);
        assertEquals(0f,BackgroundShader.amount(true,true,50,false),0);
    }
    @Test public void commentsCannotSupplyAnOutputOrEntryPoint() {
        assertNull(BackgroundShader.patch(SOURCE.replace("void main()", "// void main()")));
        assertNull(BackgroundShader.patch(SOURCE.replace("out vec4 fragColor;", "/* out vec4 fragColor; */")));
        assertNull(BackgroundShader.patch(SOURCE.replace("void main()", "void main(); //")));
        String source="/* void main(){} */\n"+SOURCE+"\n// void main(){}";
        String result=BackgroundShader.patch(source);
        assertNotNull(result);assertTrue(result.startsWith("/* void main(){} */"));
        assertTrue(result.contains("void ceNativeBackgroundMain() {"));
    }
}
