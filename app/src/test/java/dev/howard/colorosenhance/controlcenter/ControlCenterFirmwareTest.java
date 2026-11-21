package dev.howard.colorosenhance.controlcenter;

import android.view.View;
import org.junit.Test;
import static org.junit.Assert.*;

public class ControlCenterFirmwareTest {
    @Test public void readsCurrentFractionFromLegacyAnimatorFacade() throws Exception {
        var api = new NativeControlCenter.Seamless(LegacyComponent.class);
        var component = new LegacyComponent();
        assertEquals(.65f, api.progress(component), 0f);
        component.qsPanelAnimatorFacade.progress = .12f;
        assertEquals(.12f, api.progress(component), 0f);
    }

    @Test public void newerAnimatorGetterStillWinsWhenFacadeAlsoExists() throws Exception {
        var api = new NativeControlCenter.Seamless(NewComponent.class);
        assertEquals(.84f, api.progress(new NewComponent()), 0f);
    }

    static class LegacyComponent {
        final FakeStatusController fakeStatusContainerController = new FakeStatusController();
        final Animator qsPanelAnimatorFacade = new Animator(.65f);
    }

    static final class NewComponent extends LegacyComponent {
        public Animator getQsPanelAnimatorManager() { return new Animator(.84f); }
    }

    static final class FakeStatusController {
        public void updateRightArea(float fraction) { }
        public View getStatusBarContainer() { return null; }
        public View getFakeLayout() { return null; }
    }

    static final class Animator {
        float progress;
        Animator(float progress) { this.progress = progress; }
        public float getRawFraction() { return progress; }
    }
}
