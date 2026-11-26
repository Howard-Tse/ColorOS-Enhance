package dev.lsposed.fluidcloud;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.UserHandle;
import dev.howard.colorosenhance.notifications.NotificationCloudRules;
import dev.howard.colorosenhance.notifications.NotificationMetadataProvider;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class NotificationChannelMetadata {
    static void install(NotificationCloudFeature feature, Context context) {
        context.registerReceiver(new BroadcastReceiver() {
            @Override public void onReceive(Context ignored, Intent intent) {
                String pkg = intent.getStringExtra("package"), token = intent.getStringExtra("token");
                int serial = intent.getIntExtra("serial", NotificationCloudRules.MAIN_APP);
                if (!NotificationCloudRules.validPackage(pkg) || token == null || token.length() > 64
                        || serial < NotificationCloudRules.MAIN_APP) return;
                var clones = feature.cloneUsers();
                new Thread(() -> query(feature, context, pkg, token, serial, clones), "coloros-channel-query").start();
            }
        }, new IntentFilter(NotificationMetadataProvider.ACTION), NotificationMetadataProvider.PERMISSION,
                feature.main, Context.RECEIVER_EXPORTED);
    }
    private static void query(NotificationCloudFeature feature, Context context, String pkg, String token,
            int serial, Map<UserHandle, Integer> clones) {
        LinkedHashMap<String, String> channels = new LinkedHashMap<>();
        Map<Integer, String> instances = serial == NotificationCloudRules.MAIN_APP
                ? cloneInstances(context, pkg, clones) : Map.of();
        try {
            Object service = Reflect.call(Reflect.method(NotificationManager.class, "getService", Class.forName("android.app.INotificationManager")), null);
            MethodQuery query = MethodQuery.of(service.getClass());
            PackageManager packages = context.getPackageManager();
            if (serial == NotificationCloudRules.MAIN_APP) {
                query.addChannels(service, pkg, packages.getApplicationInfo(pkg, 0).uid, channels);
            } else {
                for (var profile : clones.entrySet()) if (profile.getValue() == serial) {
                    query.addChannels(service, pkg, cloneUid(packages, pkg, profile.getKey()), channels);
                    break;
                }
            }
        } catch (PackageManager.NameNotFoundException | ReflectiveOperationException | RuntimeException | LinkageError unavailable) {
            // Fall back to already active notifications for the requested instance.
        }
        String resultSource = channels.isEmpty() ? "当前通知" : "应用已创建的通知渠道";
        feature.main.post(() -> {
            if (channels.isEmpty()) for (var entry : feature.store.entries())
                if (entry.app().equals(pkg) && feature.cloneSerial(entry.value()) == serial)
                channels.put(entry.channel(), entry.channel());
            Bundle result = new Bundle(); result.putString("package", pkg); result.putString("token", token);
            result.putInt("serial", serial);
            result.putIntArray("clone_serials", instances.keySet().stream().mapToInt(Integer::intValue).toArray());
            result.putStringArray("clone_labels", instances.values().toArray(String[]::new));
            result.putStringArray("ids", channels.keySet().stream().limit(512).toArray(String[]::new));
            result.putStringArray("labels", channels.values().stream().limit(512).toArray(String[]::new)); result.putString("source", resultSource);
            try { context.getContentResolver().call(NotificationMetadataProvider.URI, "publish", null, result); }
            catch (RuntimeException unavailable) { feature.module.info("Channel metadata response unavailable"); }
        });
    }
    private static int cloneUid(PackageManager packages, String pkg, UserHandle user) throws ReflectiveOperationException {
        var identifier = Reflect.method(UserHandle.class, "getIdentifier", int.class);
        var uidForUser = Reflect.method(PackageManager.class, "getPackageUidAsUser", int.class, String.class, int.class, int.class);
        return (int) Reflect.call(uidForUser, packages, pkg, 0, Reflect.call(identifier, user));
    }
    private static Map<Integer, String> cloneInstances(Context context, String pkg, Map<UserHandle, Integer> profiles) {
        if (profiles.isEmpty()) return Map.of();
        var result = new LinkedHashMap<Integer, String>();
        var sorted = profiles.entrySet().stream().sorted(Map.Entry.comparingByValue()).toList();
        try {
            Class<?> type = Reflect.type(context.getClassLoader(), "com.oplus.multiapp.OplusMultiAppManager");
            Object manager = Reflect.call(Reflect.method(type, "getInstance", type), null);
            var registered = (List<?>) Reflect.call(Reflect.method(type, "getMultiAppUids", List.class, String.class), manager, pkg);
            var alias = Reflect.method(type, "getMultiAppAlias", String.class, String.class, int.class);
            var identifier = Reflect.method(UserHandle.class, "getIdentifier", int.class);
            int position = 0;
            for (var profile : sorted) {
                if (registered.stream().noneMatch(uid -> uid instanceof Integer id && id >= 0
                        && profile.getKey().equals(UserHandle.getUserHandleForUid(id)))) continue;
                String name = (String) Reflect.call(alias, manager, pkg, Reflect.call(identifier, profile.getKey()));
                position++;
                result.put(profile.getValue(), name == null || name.isBlank() ? "分身 " + position : name);
            }
            return result;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError unavailable) {
            result.clear();
        }
        PackageManager packages = context.getPackageManager();
        try {
            if ((packages.getApplicationInfo(pkg, 0).flags & android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0)
                return Map.of();
        } catch (PackageManager.NameNotFoundException unavailable) { return Map.of(); }
        for (var profile : sorted) try {
            if (cloneUid(packages, pkg, profile.getKey()) >= 0)
                result.put(profile.getValue(), "分身 " + (result.size() + 1));
        } catch (ReflectiveOperationException | RuntimeException | LinkageError unavailable) { /* No app in this profile. */ }
        return result;
    }
    private record MethodQuery(java.lang.reflect.Method method) {
        static MethodQuery of(Class<?> type) throws ReflectiveOperationException {
            return new MethodQuery(Reflect.uniqueMethod(type, method -> method.getName().equals("getNotificationChannelsForPackage")
                    && java.util.Arrays.equals(method.getParameterTypes(), new Class<?>[]{String.class, int.class, boolean.class})));
        }
        void addChannels(Object service, String pkg, int uid, LinkedHashMap<String, String> channels)
                throws ReflectiveOperationException {
            Object slice = Reflect.call(method, service, pkg, uid, false);
            List<?> list = (List<?>) Reflect.call(Reflect.method(slice.getClass(), "getList", List.class), slice);
            for (Object value : list) if (value instanceof NotificationChannel channel)
                channels.putIfAbsent(channel.getId(), channel.getName().toString());
        }
    }
}
