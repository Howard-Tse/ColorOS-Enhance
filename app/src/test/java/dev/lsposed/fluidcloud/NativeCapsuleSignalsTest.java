package dev.lsposed.fluidcloud;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.widget.FrameLayout;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;
import static dev.lsposed.fluidcloud.NativeCapsuleSignals.Change.*;

@RunWith(RobolectricTestRunner.class) @Config(sdk=36)
public class NativeCapsuleSignalsTest {
    @Test public void inheritedFrameworkMethodsNeverBecomeGlobalHooks() {
        Context context=org.robolectric.RuntimeEnvironment.getApplication();
        List<Method> hooked=new ArrayList<>();var signals=new NativeCapsuleSignals((m,l)->hooked.add(m));
        var root=new FrameLayout(context);root.addView(new android.widget.TextView(context));
        new CapsuleViewScope(root,signals::observe).refreshSources();assertTrue(hooked.isEmpty());
    }
    @Test public void nativeOverridesAreDiscoveredOnceAcrossDescendantReplacement() {
        Context context=org.robolectric.RuntimeEnvironment.getApplication();
        var hooked=new java.util.HashMap<String,NativeCapsuleSignals.Change>();
        var signals=new NativeCapsuleSignals((method,change)->{
            assertSame(CapsuleView.class,method.getDeclaringClass());
            assertNull(hooked.put(method.getName(),change));
        });
        var root=new FrameLayout(context);root.addView(new CapsuleView(context));
        var scope=new CapsuleViewScope(root,signals::observe);
        scope.refreshSources();
        assertEquals(java.util.Map.of("requestLayout",LAYOUT,"invalidateDrawable",PIXELS,"b",GEOMETRY),hooked);
        root.removeAllViews();root.addView(new CapsuleView(context));scope.refreshSources();
        assertEquals(3,hooked.size());
    }
    public static class CapsuleView extends FrameLayout {
        public CapsuleView(Context context){super(context);}
        @Override public void requestLayout(){super.requestLayout();}
        @Override public void invalidateDrawable(Drawable drawable){super.invalidateDrawable(drawable);}
        public void b(float fraction){setAlpha(fraction);}
    }
}
