package dev.lsposed.fluidcloud;

import android.app.Notification;
import android.os.UserHandle;
import android.service.notification.StatusBarNotification;
import io.github.libxposed.api.XposedInterface;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.function.Consumer;

/** The same per-entry visibility and redaction decisions used by the native lockscreen. */
final class NotificationLockscreenPolicy {
    private final Method locked, hidden, redaction, currentUser, userId;
    private final Field manager;
    private final NotificationCloneProfiles cloneProfiles;
    private Object provider;
    private final NotificationCloudFeature feature;
    private NotificationCardLifetime cardLifetime;
    private boolean triedCardLifetime;

    NotificationLockscreenPolicy(NotificationCloudFeature feature, ClassLoader loader, Class<?> entry)
            throws ReflectiveOperationException {
        this.feature = feature;
        Class<?> type = Reflect.type(loader, "com.android.systemui.statusbar.notification.interruption.KeyguardNotificationVisibilityProviderImpl");
        Class<?> users = Reflect.type(loader, "com.android.systemui.statusbar.NotificationLockscreenUserManagerImpl");
        manager = Reflect.field(type, "lockscreenUserManager", Object.class);
        currentUser = Reflect.method(users, "getCurrentUserId", int.class);
        userId = Reflect.method(UserHandle.class, "getIdentifier", int.class);
        NotificationCloneProfiles profiles = null;
        try { profiles = new NotificationCloneProfiles(users, currentUser, userId); }
        catch (ReflectiveOperationException | RuntimeException | LinkageError unavailable) {
            feature.failure("Notification clone profile lookup unavailable", unavailable);
        }
        cloneProfiles = profiles;
        locked = Reflect.method(type, "isLockedOrLocking", boolean.class);
        hidden = Reflect.method(type, "shouldHideNotification", boolean.class, entry);
        Method redact;
        try { redact = Reflect.method(users, "getRedactionType", int.class, entry); }
        catch (NoSuchMethodException older) { redact = Reflect.method(users, "needsRedaction", boolean.class, entry); }
        redaction = redact;
        Method listen = Reflect.method(type, "addOnStateChangedListener", void.class, Consumer.class);
        Consumer<String> policyChanged = reason -> feature.lockPolicyChanged();
        // A plugin may attach after CoreStartable.start. The pipeline also
        // provides the same live policy object when it subscribes to changes.
        for (Method method : new Method[]{Reflect.method(type, "start", void.class), listen}) {
            feature.module.hook(method).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
                Object result = chain.proceed();
                Object owner = chain.getThisObject();
                if (provider != owner) {
                    provider = owner;
                    try { Reflect.call(listen, owner, policyChanged); }
                    catch (RuntimeException failure) { provider = null; throw failure; }
                    feature.lockPolicyChanged();
                }
                return result;
            });
        }
    }

    // A default-off feature should not install a timestamp hook until its lockscreen list is used.
    private boolean hidden(Object entry, boolean showCard) {
        if (showCard && !triedCardLifetime) {
            triedCardLifetime = true;
            try { cardLifetime = NotificationCardLifetime.install(feature, hidden.getParameterTypes()[0], hidden); }
            catch (ReflectiveOperationException | RuntimeException | LinkageError unavailable) {
                feature.failure("Persistent notification card age unavailable; using normal lockscreen policy", unavailable);
            }
        }
        if (showCard && cardLifetime != null)
            return cardLifetime.evaluate(entry, () -> !Boolean.FALSE.equals(Reflect.call(hidden, provider, entry)));
        return !Boolean.FALSE.equals(Reflect.call(hidden, provider, entry));
    }

    boolean allows(Object entry, StatusBarNotification source, boolean showCard) {
        if (provider == null || source.getNotification().visibility == Notification.VISIBILITY_SECRET) return false;
        try {
            Object users = Reflect.get(manager, provider);
            if (users == null || !Boolean.TRUE.equals(Reflect.call(locked, provider))) return false;
            boolean current = Reflect.call(userId, source.getUser()).equals(Reflect.call(currentUser, users));
            if ((!current && (cloneProfiles == null || !cloneProfiles.contains(users, source.getUser())))
                    || hidden(entry, showCard)) return false;
            Object privacy = Reflect.call(redaction, users, entry);
            return Boolean.FALSE.equals(privacy) || Integer.valueOf(0).equals(privacy);
        } catch (RuntimeException | LinkageError unavailable) {
            return false;
        }
    }

    int cloneSerial(UserHandle user) {
        if (provider == null || cloneProfiles == null) return -1;
        try { return cloneProfiles.serial(Reflect.get(manager, provider), user); }
        catch (RuntimeException | LinkageError unavailable) { return -1; }
    }

    Map<UserHandle, Integer> cloneUsers() {
        if (provider == null || cloneProfiles == null) return Map.of();
        try { return cloneProfiles.snapshot(Reflect.get(manager, provider)); }
        catch (RuntimeException | LinkageError unavailable) { return Map.of(); }
    }
}
