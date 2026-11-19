package dev.howard.colorosenhance.controlcenter;

import java.util.List;
import java.util.Map;
import org.junit.Test;
import static org.junit.Assert.*;

public class ControlCenterLayoutTest {
    @Test public void newInstallAndDisabledMasterLeaveNativeBehaviorUntouched(){
        var defaults=ControlCenterConfig.from(Map.of());assertFalse(defaults.vertical(false));assertFalse(defaults.vertical(true));assertFalse(defaults.fade(true));
        var disabled=ControlCenterConfig.from(Map.of("vertical_portrait",true,"vertical_landscape",true,"fade_landscape",true));
        assertFalse(disabled.vertical(false));assertFalse(disabled.vertical(true));assertFalse(disabled.fade(true));
    }
    @Test public void orientationsAndNativePagingFadeAreIndependent(){
        var portrait=ControlCenterConfig.from(Map.of("enabled",true,"vertical_portrait",true,"fade_landscape",true));
        assertTrue(portrait.vertical(false));assertFalse(portrait.vertical(true));assertTrue(portrait.fade(true));assertFalse(portrait.fade(false));
        var landscape=ControlCenterConfig.from(Map.of("enabled",true,"vertical_landscape",true,"fade_landscape",true));
        assertFalse(landscape.vertical(false));assertTrue(landscape.vertical(true));assertFalse(landscape.fade(true));
    }
    @Test public void boundaryHapticsDefaultOffAndRequireTheActiveVerticalOrientation(){
        assertFalse(ControlCenterConfig.from(Map.of()).edgeHaptics());
        var portrait=ControlCenterConfig.from(Map.of("enabled",true,"vertical_portrait",true,"edge_haptics",true));
        assertTrue(portrait.feedback(false));assertFalse(portrait.feedback(true));
        assertFalse(ControlCenterConfig.from(Map.of("vertical_portrait",true,"edge_haptics",true)).feedback(false));
    }
    @Test public void flattenPreservesOrderSpansAndNativePageGapsWithoutMutatingSource(){
        var source=List.of(new ControlCenterLayout.Cell(0,0,0,2,2),new ControlCenterLayout.Cell(0,2,0,2,1),
                new ControlCenterLayout.Cell(1,0,0,1,2),new ControlCenterLayout.Cell(1,1,0,2,2));
        var plan=ControlCenterLayout.flatten(source,4,4);assertNotNull(plan);assertEquals(6,plan.rows());
        assertEquals(List.of(new ControlCenterLayout.Position(0,0),new ControlCenterLayout.Position(2,0),
                new ControlCenterLayout.Position(0,4),new ControlCenterLayout.Position(1,4)),plan.positions());
        assertEquals(1,source.get(2).page());assertEquals(0,source.get(2).row());
        assertEquals(532,ControlCenterLayout.extraHeight(4,6,266));
    }
    @Test public void malformedOrOverlappingNativeGeometryFailsClosed(){
        assertNull(ControlCenterLayout.flatten(List.of(new ControlCenterLayout.Cell(0,3,0,2,1)),4,4));
        assertNull(ControlCenterLayout.flatten(List.of(new ControlCenterLayout.Cell(0,0,3,1,2)),4,4));
        assertNull(ControlCenterLayout.flatten(List.of(new ControlCenterLayout.Cell(-1,0,0,1,1)),4,4));
        assertNull(ControlCenterLayout.flatten(List.of(new ControlCenterLayout.Cell(0,0,0,2,2),new ControlCenterLayout.Cell(0,1,1,1,1)),4,4));
        assertNull(ControlCenterLayout.flatten(List.of(),0,4));
    }
    @Test public void lastPageIsTrimmedButNativeViewportDoesNotShrink(){
        assertEquals(4,ControlCenterLayout.flatten(List.of(),4,4).rows());
        var plan=ControlCenterLayout.flatten(List.of(new ControlCenterLayout.Cell(2,0,0,1,1)),4,4);
        assertEquals(9,plan.rows());assertEquals(0,ControlCenterLayout.extraHeight(4,4,266));
        assertEquals(1_000_000,ControlCenterLayout.extraHeight(1,8192,Integer.MAX_VALUE));
    }
    @Test public void bottomStripAndUpwardGesturesAtTheBottomBelongToNativeCollapse(){
        assertTrue(ControlCenterLayout.keepVerticalGesture(0,-100,false,false));
        assertFalse(ControlCenterLayout.keepVerticalGesture(0,-100,true,false));
        assertFalse(ControlCenterLayout.keepVerticalGesture(0,-100,false,true));
        assertTrue(ControlCenterLayout.keepVerticalGesture(0,100,false,true));
        assertFalse(ControlCenterLayout.keepVerticalGesture(100,10,false,false));
    }
    @Test public void bothSettingsFrontendsUseTheNewIndependentCatalogGroup(){
        var group=dev.howard.colorosenhance.SettingsCatalog.find(ControlCenterConfig.GROUP);assertNotNull(group);
        assertEquals("启用控制中心增强",dev.howard.colorosenhance.SettingsCatalog.masterTitle(group));
        for(String key:List.of("enabled","vertical_portrait","vertical_landscape","fade_landscape","edge_haptics"))
            assertTrue(dev.howard.colorosenhance.SettingsCoordinator.validValue(ControlCenterConfig.GROUP,key,true));
        var fade=group.items().stream().filter(item->item.key().equals("fade_landscape")).findFirst().orElseThrow();
        assertTrue(fade.visible(Map.of()));assertFalse(fade.visible(Map.of("vertical_landscape",true)));
    }
}
