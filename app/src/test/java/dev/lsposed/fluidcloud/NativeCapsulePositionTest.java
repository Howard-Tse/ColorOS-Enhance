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
        var position = createPosition();
        var host = newHost(position);
        var container = (Container) host.getChildAt(0);
        var capsule = container.getChildAt(0);
        position.refresh(new CapsulePosition(-25, -8), CapsulePosition.OEM);
        assertFrame(container, 55, -8);
        host.requestLayout();
        layout(host);
        assertFrame(container, 55, -8);
        position.refresh(new CapsulePosition(120, 32), CapsulePosition.OEM);
        assertEquals(320, container.getLeft() + capsule.getRight());
        assertEquals(60, container.getTop() + capsule.getBottom());
        position.close();
        assertFrame(container, 80, 0);
    }

    @Test public void containerOnlyLayoutAfterCollapseReappliesOffsetToTheNewNativeFrame() throws Exception {
        var position = createPosition();
        var container = (Container) newHost(position).getChildAt(0);
        position.refresh(new CapsulePosition(22, -7), CapsulePosition.OEM);
        for (int i = 0; i < 3; i++) {
            container.layout(80, 0, 240, 60);
            assertFrame(container, 102, -7);
        }
        container.layout(90, 5, 250, 65);
        assertFrame(container, 112, -2);
        position.refresh(CapsulePosition.OEM, CapsulePosition.OEM);
        assertFrame(container, 90, 5);
    }

    @Test public void newlyCreatedLockScreenContainerAndReturningCapsuleUseCurrentOffset() throws Exception {
        var position = createPosition();
        var statusBar = newHost(position);
        position.refresh(new CapsulePosition(-25, 9), CapsulePosition.OEM);
        var keyguard = newHost(position);
        var lockContainer = (Container) keyguard.getChildAt(0);
        var capsule = lockContainer.getChildAt(0);

        assertFrame(lockContainer, 55, 9);
        capsule.setVisibility(View.INVISIBLE);
        position.afterVisibility(capsule);
        lockContainer.layout(80, 0, 240, 60);
        assertFrame(lockContainer, 55, 9);
        capsule.setVisibility(View.VISIBLE);
        position.afterVisibility(capsule);
        assertFrame(lockContainer, 55, 9);
        position.refresh(new CapsulePosition(12, -4), CapsulePosition.OEM);
        assertFrame(lockContainer, 92, -4);
        assertFrame(statusBar.getChildAt(0), 92, -4);
    }

    @Test public void cameraOnlyMiniKeepsNativePosition() throws Exception {
        var position = createPosition();
        var host = newHost(position);
        var container = (Container) host.getChildAt(0);
        position.refresh(new CapsulePosition(22, 8), CapsulePosition.OEM);
        container.getChildAt(0).setVisibility(View.INVISIBLE);
        container.addView(new View(host.getContext()), new FrameLayout.LayoutParams(24, 24, Gravity.CENTER));
        layout(host);
        assertFrame(container, 80, 0);
    }

    @Test public void existingOffsetsBecomePortraitOnlyAndBothDirectionsUseTheSameLimits() {
        var legacy = CloudConfig.from(Map.of("enabled", true, "capsule_offset_x_dp", 42, "capsule_offset_y_dp", -10));
        assertEquals(new CapsulePosition(42, -10), legacy.capsulePosition(false));
        assertEquals(CapsulePosition.OEM, legacy.capsulePosition(true));
        var config = CloudConfig.from(Map.of("enabled", true, "capsule_offset_x_dp", -900,
                "capsule_landscape_offset_x_dp", 900, "capsule_landscape_offset_y_dp", -900,
                "landscape_follow_portrait", true,
                "capsule_offset_y_dp", 900, "card_corner_dp", 500, "card_corners_enabled", true));
        assertEquals(new CapsulePosition(-120, 32), config.capsulePosition(false));
        assertEquals(new CapsulePosition(120, -32), config.capsulePosition(true));
        var disabled = CloudConfig.from(Map.of("capsule_offset_x_dp", 42, "capsule_landscape_offset_x_dp", 55));
        assertEquals(CapsulePosition.OEM, disabled.capsulePosition(false));
        assertEquals(CapsulePosition.OEM, disabled.capsulePosition(true));
        assertEquals(96, config.corners().card().dp());
    }

    @Test public void rotationSelectsTheOffsetsForTheCurrentOrientation() throws Exception {
        RuntimeEnvironment.setQualifiers("+port");
        var position = createPosition();
        var host = newHost(position);
        var container = (Container) host.getChildAt(0);
        var portrait = new CapsulePosition(-25, -8);
        position.refresh(portrait, CapsulePosition.OEM);
        assertFrame(container, 55, -8);

        RuntimeEnvironment.setQualifiers("+land");
        layout(host, 480);
        assertFrame(container, 160, 0);
        position.refresh(portrait, new CapsulePosition(35, 10));
        assertFrame(container, 195, 10);

        RuntimeEnvironment.setQualifiers("+port");
        layout(host);
        assertFrame(container, 55, -8);
    }

    private static NativeCapsulePosition createPosition() throws ReflectiveOperationException {
        return new NativeCapsulePosition(name -> switch (name.substring(name.lastIndexOf('.') + 1)) {
            case "CapsuleContainerRoot" -> FrameLayout.class;
            case "CapsuleContainer" -> Container.class;
            case "CapsuleView" -> Capsule.class;
            default -> throw new ClassNotFoundException(name);
        });
    }

    private static FrameLayout newHost(NativeCapsulePosition position) {
        var context = RuntimeEnvironment.getApplication();
        var host = new FrameLayout(context);
        var container = new Container(context, position);
        host.addView(container, new FrameLayout.LayoutParams(160, -1, Gravity.CENTER));
        container.addView(new Capsule(context), new FrameLayout.LayoutParams(100, 24, Gravity.CENTER));
        layout(host);
        return host;
    }

    private static void assertFrame(View view, int left, int top) {
        assertEquals(left, view.getLeft());
        assertEquals(top, view.getTop());
    }

    private static void layout(View view) {
        layout(view, 320);
    }

    private static void layout(View view, int width) {
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(60, View.MeasureSpec.EXACTLY));
        view.layout(0, 0, width, 60);
    }

    private static final class Container extends FrameLayout {
        private final NativeCapsulePosition position;

        Container(Context context, NativeCapsulePosition position) {
            super(context);
            this.position = position;
        }

        @Override protected void onLayout(boolean changed, int l, int t, int r, int b) {
            super.onLayout(changed, l, t, r, b);
            position.afterLayout(this);
        }
    }

    private static final class Capsule extends View {
        Capsule(Context context) { super(context); }
    }
}
