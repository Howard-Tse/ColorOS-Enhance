package dev.lsposed.fluidcloud;

import android.os.UserHandle;
import android.util.SparseArray;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;

/** Reads the same current-profile cache that SystemUI uses to filter its notification list. */
final class NotificationCloneProfiles {
    private final Method currentUser, currentProfiles, identifier, cloneProfile, enabled, handle;
    private final Field lock, serialNumber;
    private final int ownerId;

    NotificationCloneProfiles(Class<?> manager, Method currentUser, Method identifier)
            throws ReflectiveOperationException {
        Class<?> info = Reflect.type(manager.getClassLoader(), "android.content.pm.UserInfo");
        this.currentUser = currentUser;
        this.identifier = identifier;
        ownerId = (int) Reflect.call(identifier, android.os.Process.myUserHandle());
        currentProfiles = Reflect.method(manager, "getCurrentProfiles", SparseArray.class);
        lock = Reflect.field(manager, "mLock", Object.class);
        cloneProfile = Reflect.method(info, "isCloneProfile", boolean.class);
        enabled = Reflect.method(info, "isEnabled", boolean.class);
        handle = Reflect.method(info, "getUserHandle", UserHandle.class);
        serialNumber = Reflect.field(info, "serialNumber", int.class);
    }

    boolean contains(Object manager, UserHandle user) {
        return serial(manager, user) >= 0;
    }

    int serial(Object manager, UserHandle user) {
        int id = (int) Reflect.call(identifier, user);
        if (id < 0 || id == ownerId) return -1;
        synchronized (Reflect.get(lock, manager)) {
            if ((int) Reflect.call(currentUser, manager) != ownerId) return -1;
            Object info = ((SparseArray<?>) Reflect.call(currentProfiles, manager)).get(id);
            return isClone(info) ? (int) Reflect.get(serialNumber, info) : -1;
        }
    }

    Map<UserHandle, Integer> snapshot(Object manager) {
        synchronized (Reflect.get(lock, manager)) {
            if ((int) Reflect.call(currentUser, manager) != ownerId) return Map.of();
            SparseArray<?> profiles = (SparseArray<?>) Reflect.call(currentProfiles, manager);
            Map<UserHandle, Integer> clones = new LinkedHashMap<>();
            for (int i = 0; i < profiles.size(); i++) {
                Object info = profiles.valueAt(i);
                if (isClone(info)) clones.put((UserHandle) Reflect.call(handle, info), (int) Reflect.get(serialNumber, info));
            }
            return Map.copyOf(clones);
        }
    }

    private boolean isClone(Object info) {
        return info != null && Boolean.TRUE.equals(Reflect.call(cloneProfile, info))
                && Boolean.TRUE.equals(Reflect.call(enabled, info));
    }
}
