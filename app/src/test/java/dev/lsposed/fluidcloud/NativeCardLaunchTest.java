package dev.lsposed.fluidcloud;

import android.app.PendingIntent;
import android.os.Bundle;
import android.view.View;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class) @Config(sdk=36,manifest=Config.NONE)
public class NativeCardLaunchTest {
    @Test public void discoversTheSharedNativeLaunchContractWithoutAnObfuscatedMethodName(){
        assertEquals("launch",NativeCardLaunch.find(Current.class).orElseThrow().getName());
        assertEquals("a",NativeCardLaunch.find(Older.class).orElseThrow().getName());
    }
    @Test public void ambiguousOrAbsentContractsDoNotHookAnUnrelatedMethod(){
        assertTrue(NativeCardLaunch.find(Ambiguous.class).isEmpty());assertTrue(NativeCardLaunch.find(Object.class).isEmpty());
    }
    @Test public void deferredLaunchKeepsItsOwnArgumentsWhenTheProviderReusesItsListAndBundle(){
        List<String> intents=new ArrayList<>(List.of("first"));Bundle bundle=new Bundle();bundle.putInt("taskId",7);
        Object owner=new Object();Object[] source={owner,null,intents,true,false,null,bundle};Object[] snapshot=NativeCardLaunch.snapshot(source);
        intents.clear();bundle.putInt("taskId",8);source[0]=new Object();
        assertSame(owner,snapshot[0]);assertEquals(List.of("first"),snapshot[2]);assertEquals(7,((Bundle)snapshot[6]).getInt("taskId"));
        assertEquals(true,snapshot[3]);assertEquals(false,snapshot[4]);
    }
    @Test public void mediaControlBroadcastsAndEmptyActionsDoNotCollapseThePanel(){
        var context=org.robolectric.RuntimeEnvironment.getApplication();
        PendingIntent control=PendingIntent.getBroadcast(context,1,new android.content.Intent("media.control"),PendingIntent.FLAG_IMMUTABLE);
        Object[] args={new Object(),null,List.of(),false,false,control,null};
        assertFalse(NativeCardLaunch.activityRequest(args));args[5]=null;assertFalse(NativeCardLaunch.activityRequest(args));
        args[6]=new Bundle();assertFalse(NativeCardLaunch.activityRequest(args));
    }
    @Test public void activityPendingIntentsTaskRestoresAndIntentCandidatesUsePanelDismissal(){
        var context=org.robolectric.RuntimeEnvironment.getApplication();
        Object[] args={new Object(),null,List.of(),false,false,PendingIntent.getActivity(context,2,new android.content.Intent("activity.open"),PendingIntent.FLAG_IMMUTABLE),null};
        assertTrue(NativeCardLaunch.activityRequest(args));args[5]=null;Bundle task=new Bundle();task.putInt("taskId",7);args[6]=task;assertTrue(NativeCardLaunch.activityRequest(args));
        args[6]=null;args[2]=List.of(new android.content.Intent("activity.open"));assertTrue(NativeCardLaunch.activityRequest(args));
    }
    @Test public void pendingActivityCandidatesKeepIndependentIntentFields(){
        android.content.Intent intent=new android.content.Intent("original");Object[] args={null,null,List.of(intent),false,false,null,null};
        Object[] snapshot=NativeCardLaunch.snapshot(args);intent.setAction("reused");
        assertEquals("original",((android.content.Intent)((List<?>)snapshot[2]).getFirst()).getAction());
    }
    public static class Base{public boolean launch(Object model,View view,List<?> intents,boolean a,boolean b,PendingIntent intent,Bundle bundle){return true;}}
    public static class Current extends Base{}
    public static class Older{public boolean a(Object model,View view,List<?> intents,boolean a,boolean b,PendingIntent intent,Bundle bundle){return true;}}
    public static class Ambiguous extends Base{public boolean second(Object model,View view,List<?> intents,boolean a,boolean b,PendingIntent intent,Bundle bundle){return true;}}
}
