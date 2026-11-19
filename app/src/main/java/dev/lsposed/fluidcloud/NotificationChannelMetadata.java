package dev.lsposed.fluidcloud;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import dev.howard.colorosenhance.notifications.NotificationCloudRules;
import dev.howard.colorosenhance.notifications.NotificationMetadataProvider;
import java.util.LinkedHashMap;
import java.util.List;

final class NotificationChannelMetadata {
    static void install(NotificationCloudFeature feature, Context context) {
        context.registerReceiver(new BroadcastReceiver() {
            @Override public void onReceive(Context ignored, Intent intent) {
                String pkg = intent.getStringExtra("package"), token = intent.getStringExtra("token");
                if (!NotificationCloudRules.validPackage(pkg) || token == null || token.length() > 64) return;
                new Thread(() -> query(feature, context, pkg, token), "coloros-channel-query").start();
            }
        }, new IntentFilter(NotificationMetadataProvider.ACTION), NotificationMetadataProvider.PERMISSION,
                feature.main, Context.RECEIVER_EXPORTED);
    }
    private static void query(NotificationCloudFeature feature, Context context, String pkg, String token) {
        LinkedHashMap<String, String> channels = new LinkedHashMap<>(); String source = "当前通知";
        try {
            int uid = context.getPackageManager().getApplicationInfo(pkg, 0).uid;
            Object service = Reflect.call(Reflect.method(NotificationManager.class, "getService", Class.forName("android.app.INotificationManager")), null);
            MethodQuery query = MethodQuery.of(service.getClass());
            Object slice = Reflect.call(query.method, service, pkg, uid, false);
            List<?> list = (List<?>) Reflect.call(Reflect.method(slice.getClass(), "getList", List.class), slice);
            for (Object value : list) if (value instanceof NotificationChannel channel)
                channels.put(channel.getId(), channel.getName().toString());
            source = "应用已创建的通知渠道";
        } catch (ReflectiveOperationException | android.content.pm.PackageManager.NameNotFoundException | RuntimeException | LinkageError unavailable) { /* Active-channel fallback below. */ }
        String resultSource = source;
        feature.main.post(() -> {
            if (channels.isEmpty()) for (var entry : feature.store.entries()) if (entry.app().equals(pkg))
                channels.put(entry.channel(), entry.channel());
            Bundle result = new Bundle(); result.putString("package", pkg); result.putString("token", token);
            result.putStringArray("ids", channels.keySet().stream().limit(512).toArray(String[]::new));
            result.putStringArray("labels", channels.values().stream().limit(512).toArray(String[]::new)); result.putString("source", resultSource);
            try { context.getContentResolver().call(NotificationMetadataProvider.URI, "publish", null, result); }
            catch (RuntimeException unavailable) { feature.module.info("Channel metadata response unavailable"); }
        });
    }
    private record MethodQuery(java.lang.reflect.Method method) {
        static MethodQuery of(Class<?> type) throws ReflectiveOperationException {
            return new MethodQuery(Reflect.uniqueMethod(type, method -> method.getName().equals("getNotificationChannelsForPackage")
                    && java.util.Arrays.equals(method.getParameterTypes(), new Class<?>[]{String.class, int.class, boolean.class})));
        }
    }
}
