package dev.lsposed.fluidcloud;

import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import java.util.Map;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.assertEquals;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 36, qualifiers = "mdpi", application = android.app.Application.class)
public class NativeCapsulePositionTest {
    @Test public void offsetsMoveTheTouchFrameAndSurviveLayoutWithoutAccumulating() throws Exception {
        var context = RuntimeEnvironment.getApplication();
        var host = new FrameLayout(context);
        var container = new Container(context);
        host.addView(container, new FrameLayout.LayoutParams(160, -1, Gravity.CENTER));
        var capsule = new View(context);
        container.addView(capsule, new FrameLayout.LayoutParams(100, 24, Gravity.CENTER));
        var position = new NativeCapsulePosition(name -> switch (name.substring(name.lastIndexOf('.') + 1)) {
            case "CapsuleContainerRoot" -> FrameLayout.class;
            case "CapsuleContainer" -> Container.class;
            case "CapsuleView" -> View.class;
            default -> throw new ClassNotFoundException(name);
        });
        layout(host);
        position.refresh(host, new CapsulePosition(-25, -8));
        layout(host);
        assertEquals(55, container.getLeft());
        assertEquals(-8, container.getTop());
        host.requestLayout();
        layout(host);
        assertEquals(55, container.getLeft());
        assertEquals(-8, container.getTop());
        position.refresh(host, new CapsulePosition(120, 32));
        layout(host);
        assertEquals(320, container.getLeft() + capsule.getRight());
        assertEquals(60, container.getTop() + capsule.getBottom());
        position.close();
        layout(host);
        assertEquals(80, container.getLeft());
        assertEquals(0, container.getTop());
    }

    @Test public void newParametersPreserveFactoryDefaultsAndUseTheSameLimitsAtRuntime() {
        assertEquals(CapsulePosition.OEM, CloudConfig.from(Map.of("enabled", true)).capsulePosition());
        assertEquals(CapsulePosition.OEM, CloudConfig.from(Map.of("capsule_offset_x_dp", 42)).capsulePosition());
        var config = CloudConfig.from(Map.of("enabled", true, "capsule_offset_x_dp", -900,
                "capsule_offset_y_dp", 900, "card_corner_dp", 500, "card_corners_enabled", true));
        assertEquals(new CapsulePosition(-120, 32), config.capsulePosition());
        assertEquals(96, config.corners().card().dp());
    }

    private static void layout(View view) {
        view.measure(View.MeasureSpec.makeMeasureSpec(320, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(60, View.MeasureSpec.EXACTLY));
        view.layout(0, 0, 320, 60);
    }

    private static final class Container extends FrameLayout {
        Container(Context context) { super(context); }
    }
}
