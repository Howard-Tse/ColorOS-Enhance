package dev.howard.colorosenhance.diagnostics;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.provider.Settings;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

final class DiagnosticStore extends SQLiteOpenHelper {
    static final long RETENTION_MS = 86_400_000;
    static final int LIMIT = 512;
    private static DiagnosticStore instance;
    record Signal(DiagnosticPoint point, DiagnosticPoint.State state, long time) { }
    record Latest(Signal signal, int boot, long update) { }
    private final long updated;

    static synchronized DiagnosticStore get(Context context) {
        if (instance == null) instance = new DiagnosticStore(context.getApplicationContext());
        return instance;
    }

    private DiagnosticStore(Context context) {
        super(context, "module_diagnostics.db", null, 1);
        long update;
        try { update = context.getPackageManager().getPackageInfo(context.getPackageName(), 0).lastUpdateTime; }
        catch (android.content.pm.PackageManager.NameNotFoundException absent) { update = -1; }
        updated = update;
    }

    static int boot(Context context) { return Settings.Global.getInt(context.getContentResolver(), Settings.Global.BOOT_COUNT, -1); }
    long appUpdated() { return updated; }

    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE events (_id INTEGER PRIMARY KEY, point TEXT NOT NULL, state TEXT NOT NULL, time INTEGER NOT NULL)");
        db.execSQL("CREATE TABLE latest (point TEXT PRIMARY KEY, state TEXT NOT NULL, time INTEGER NOT NULL, boot INTEGER NOT NULL, app_update INTEGER NOT NULL)");
    }

    @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) { }

    void record(List<Signal> signals, int boot, long now) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            for (Signal signal : signals) {
                ContentValues values = new ContentValues();
                values.put("point", signal.point.name()); values.put("state", signal.state.name()); values.put("time", signal.time);
                if (signal.time >= now - RETENTION_MS) db.insertOrThrow("events", null, values);
                values.put("boot", boot); values.put("app_update", updated);
                db.replaceOrThrow("latest", null, values);
            }
            trim(db, now);
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
    }

    private static void trim(SQLiteDatabase db, long now) {
        db.delete("events", "time < ?", new String[]{Long.toString(now - RETENTION_MS)});
        db.execSQL("DELETE FROM events WHERE _id IN (SELECT _id FROM events ORDER BY _id DESC LIMIT -1 OFFSET " + LIMIT + ")");
    }

    List<Signal> recent(long now, long duration) {
        SQLiteDatabase db = getWritableDatabase();
        trim(db, now);
        List<Signal> result = new ArrayList<>();
        try (Cursor cursor = db.query("events", new String[]{"point", "state", "time"}, "time >= ? AND time <= ?",
                new String[]{Long.toString(now - Math.min(duration, RETENTION_MS)), Long.toString(now)}, null, null, "time ASC, _id ASC")) {
            while (cursor.moveToNext()) result.add(signal(cursor));
        }
        return result;
    }

    Map<DiagnosticPoint, Latest> latest() {
        Map<DiagnosticPoint, Latest> result = new EnumMap<>(DiagnosticPoint.class);
        try (Cursor cursor = getReadableDatabase().query("latest", new String[]{"point", "state", "time", "boot", "app_update"},
                null, null, null, null, null)) {
            while (cursor.moveToNext()) {
                Signal signal = signal(cursor);
                result.put(signal.point, new Latest(signal, cursor.getInt(3), cursor.getLong(4)));
            }
        }
        return result;
    }

    private static Signal signal(Cursor cursor) {
        return new Signal(DiagnosticPoint.valueOf(cursor.getString(0)), DiagnosticPoint.State.valueOf(cursor.getString(1)), cursor.getLong(2));
    }
}
