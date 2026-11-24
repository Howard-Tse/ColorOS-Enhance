package dev.lsposed.fluidcloud;

import android.service.notification.StatusBarNotification;
import android.view.View;
import dev.howard.colorosenhance.notifications.NotificationCloudRules;
import dev.howard.colorosenhance.notifications.NotificationCloudStore;
import java.util.List;
import java.util.Set;

interface NotificationCloudRenderer {
    ClassLoader loader();
    void update(List<NotificationCloudStore.Entry<StatusBarNotification>> notifications, NotificationCloudRules rules,
                Set<String> autoExpand);
    default void update(List<NotificationCloudStore.Entry<StatusBarNotification>> notifications, NotificationCloudRules rules) {
        update(notifications, rules, Set.of());
    }
    boolean available();
    default boolean supportsGroupPager() { return false; }
    default void showShadePage(NotificationShadePages.Page page) throws ReflectiveOperationException {
        throw new UnsupportedOperationException("Native grouped media layout unavailable");
    }
    int capsuleWidthLimit(View view);
    boolean open(String key);
    void closeCard(Runnable continuation);
    void close();
}
