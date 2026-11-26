package dev.howard.colorosenhance.notifications;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.SystemClock;
import android.os.UserHandle;
import dev.howard.colorosenhance.BuildConfig;
import java.util.LinkedHashMap;
import java.util.Map;

/** Memory-only, authenticated channel-name discovery. Never accepts notification content. */
public final class NotificationMetadataProvider extends ContentProvider {
    public static final String ACTION = BuildConfig.PUBLIC_APPLICATION_ID + ".REQUEST_NOTIFICATION_CHANNELS";
    public static final String PERMISSION = BuildConfig.PUBLIC_APPLICATION_ID + ".permission.NOTIFICATION_SETTINGS";
    public static final Uri URI = Uri.parse("content://" + BuildConfig.PUBLIC_APPLICATION_ID + ".notification_metadata");
    public static final Uri ALERT_STATUS = URI.buildUpon().appendPath("alert_status").build();
    public static final String LEGACY_COLLECTION_TAG = "coloros_enhance_notification_cloud";
    public static final int LEGACY_COLLECTION_ID = 0x43454e43;
    private record Cached(Bundle channels, long time) { }
    private record Request(String token, long time) { }
    private record Instance(String pkg, int serial) { }
    private final Map<Instance, Cached> cache = new LinkedHashMap<>();
    private final Map<Instance, Request> pending = new LinkedHashMap<>();
    private Bundle alertRenderer = Bundle.EMPTY;
    @Override public boolean onCreate() { return true; }
    private boolean platformUid(int uid, String pkg) {
        try { return getContext().getPackageManager().getApplicationInfo(pkg, 0).uid == uid
                && getContext().getPackageManager().checkSignatures("android", pkg) == android.content.pm.PackageManager.SIGNATURE_MATCH; }
        catch (android.content.pm.PackageManager.NameNotFoundException missing) { return false; }
    }
    @Override public synchronized Bundle call(String method, String arg, Bundle extras) {
        int uid = Binder.getCallingUid();
        if ("read_alert_renderer".equals(method)) {
            if (uid != android.os.Process.SYSTEM_UID) throw new SecurityException("Alert status caller denied");
            return new Bundle(alertRenderer);
        }
        boolean writer = platformUid(uid, "com.android.systemui");
        if ("publish_alert_renderer".equals(method)) {
            if (!writer) throw new SecurityException("Alert status writer denied");
            Bundle state = new Bundle();
            if (extras != null && extras.getBinder("renderer") != null) {
                state.putBinder("renderer", extras.getBinder("renderer"));
                state.putParcelableArrayList("clone_users", extras.getParcelableArrayList("clone_users", UserHandle.class));
                state.putIntArray("clone_serials", extras.getIntArray("clone_serials"));
            }
            alertRenderer = state;
            long identity = Binder.clearCallingIdentity();
            try { getContext().getContentResolver().notifyChange(ALERT_STATUS, null); }
            finally { Binder.restoreCallingIdentity(identity); }
            return Bundle.EMPTY;
        }
        boolean reader = uid == android.os.Process.myUid() || platformUid(uid, "com.android.settings");
        if (method.equals("clear_legacy_collection")) {
            if (!writer) throw new SecurityException("Collection caller denied");
            long identity = Binder.clearCallingIdentity();
            try {
                clearLegacyCollection(getContext());
                return Bundle.EMPTY;
            } finally { Binder.restoreCallingIdentity(identity); }
        }
        if (!(method.equals("publish") ? writer : reader)) throw new SecurityException("Channel metadata caller denied");
        String pkg = extras == null ? null : extras.getString("package");
        if (!NotificationCloudRules.validPackage(pkg)) return new Bundle();
        int serial = extras.getInt("serial", NotificationCloudRules.MAIN_APP);
        if (serial < NotificationCloudRules.MAIN_APP) return new Bundle();
        Instance instance = new Instance(pkg, serial);
        long now = SystemClock.elapsedRealtime();
        pending.entrySet().removeIf(entry -> now - entry.getValue().time() > 15000);
        cache.entrySet().removeIf(entry -> now - entry.getValue().time() > 300000);
        if (method.equals("publish")) {
            Request request = pending.get(instance);
            if (request != null && request.token().equals(extras.getString("token"))) {
                String[] ids = extras.getStringArray("ids"), labels = extras.getStringArray("labels");
                if (ids != null && labels != null && ids.length == labels.length && ids.length <= 512) {
                    Bundle value = new Bundle(); value.putStringArray("ids", ids); value.putStringArray("labels", labels);
                    value.putString("source", extras.getString("source", "当前通知")); value.putBoolean("ready", true);
                    if (serial == NotificationCloudRules.MAIN_APP) {
                        int[] cloneSerials = extras.getIntArray("clone_serials");
                        String[] cloneLabels = extras.getStringArray("clone_labels");
                        if (cloneSerials != null && cloneLabels != null && cloneSerials.length == cloneLabels.length
                                && cloneSerials.length <= 20) {
                            value.putIntArray("clone_serials", cloneSerials);
                            value.putStringArray("clone_labels", cloneLabels);
                        }
                    }
                    cache.put(instance, new Cached(value, now)); pending.remove(instance);
                }
            }
        } else if (method.equals("request") && !pending.containsKey(instance)) {
            cache.remove(instance);
            String token = java.util.UUID.randomUUID().toString(); pending.put(instance, new Request(token, now));
            long identity = Binder.clearCallingIdentity();
            try { getContext().sendBroadcast(new Intent(ACTION).setPackage("com.android.systemui")
                    .putExtra("package", pkg).putExtra("serial", serial).putExtra("token", token)); }
            finally { Binder.restoreCallingIdentity(identity); }
        }
        Cached result = cache.get(instance); return result == null ? new Bundle() : new Bundle(result.channels());
    }
    static void clearLegacyCollection(android.content.Context context) {
        context.getSystemService(android.app.NotificationManager.class).cancel(LEGACY_COLLECTION_TAG, LEGACY_COLLECTION_ID);
    }
    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) { return null; }
    @Override public String getType(Uri uri) { return null; }
    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri uri, String selection, String[] args) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] args) { throw new UnsupportedOperationException(); }
}
