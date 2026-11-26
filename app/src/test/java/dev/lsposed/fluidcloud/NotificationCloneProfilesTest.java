package dev.lsposed.fluidcloud;

import android.os.UserHandle;
import android.util.SparseArray;
import java.lang.reflect.Method;
import java.util.Map;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class) @Config(sdk = 36)
public class NotificationCloneProfilesTest {
    private static final String CLONE = "android.os.usertype.profile.CLONE";
    private static final String WORK = "android.os.usertype.profile.MANAGED";

    @Test public void includesEveryEnabledCloneOfTheCurrentOwnerAndFollowsProfileChanges() throws Exception {
        Method identifier = Reflect.method(UserHandle.class, "getIdentifier", int.class);
        int owner = (int) Reflect.call(identifier, android.os.Process.myUserHandle());
        var manager = new ProfileManager(owner);
        var resolver = new NotificationCloneProfiles(ProfileManager.class,
                Reflect.method(ProfileManager.class, "getCurrentUserId", int.class), identifier);
        manager.profiles.put(11, profile(11, CLONE, 0));
        manager.profiles.put(21, profile(21, CLONE, 0));
        manager.profiles.put(31, profile(31, CLONE, 0x40)); // FLAG_DISABLED
        manager.profiles.put(41, profile(41, WORK, 0));
        var first = UserHandle.getUserHandleForUid(1_112_345);
        var second = UserHandle.getUserHandleForUid(2_112_345);

        assertTrue(resolver.contains(manager, first));
        assertTrue(resolver.contains(manager, second));
        assertFalse(resolver.contains(manager, UserHandle.getUserHandleForUid(3_112_345)));
        assertFalse(resolver.contains(manager, UserHandle.getUserHandleForUid(4_112_345)));
        assertEquals(Map.of(first, 111, second, 121), resolver.snapshot(manager));

        manager.profiles.remove(11);
        assertFalse(resolver.contains(manager, first));
        assertEquals(Map.of(second, 121), resolver.snapshot(manager));
        manager.profiles.put(21, profile(21, CLONE, 0, 389));
        assertEquals(389, resolver.serial(manager, second));
        manager.currentUser = owner + 1;
        assertFalse(resolver.contains(manager, second));
        assertTrue(resolver.snapshot(manager).isEmpty());
    }

    private static Object profile(int id, String type, int flags) throws ReflectiveOperationException {
        return profile(id, type, flags, id + 100);
    }
    private static Object profile(int id, String type, int flags, int serial) throws ReflectiveOperationException {
        var info = Class.forName("android.content.pm.UserInfo");
        Object profile = info.getConstructor(int.class, String.class, String.class, int.class, String.class)
                .newInstance(id, "test", null, flags, type);
        Reflect.field(info, "serialNumber", int.class).setInt(profile, serial);
        return profile;
    }

    private static final class ProfileManager {
        private final Object mLock = new Object();
        private final SparseArray<Object> profiles = new SparseArray<>();
        private int currentUser;

        ProfileManager(int currentUser) { this.currentUser = currentUser; }
        int getCurrentUserId() { return currentUser; }
        SparseArray<Object> getCurrentProfiles() { return profiles; }
    }
}
