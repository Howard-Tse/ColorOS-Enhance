package dev.howard.colorosenhance.diagnostics;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.Bundle;
import dev.howard.colorosenhance.BuildConfig;
import java.io.File;
import java.io.IOException;
import java.io.Writer;
import java.lang.ref.WeakReference;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Services the module's on-demand export from the process that owns the active SDK loader. */
public final class SeedlingSdkDiagnostics {
    static final String PACKAGE = "com.oplus.seedling.pluginapp";
    private static final String SYSTEM_UI = "com.android.systemui";
    private static final String ACTION = BuildConfig.PUBLIC_APPLICATION_ID + ".EXPORT_SEEDLING_INTERFACES";
    private static final String PERMISSION = BuildConfig.PUBLIC_APPLICATION_ID + ".permission.EXPORT_SEEDLING_DIAGNOSTICS";
    private static final int MAX_REPORT_CHARS = 160_000; // Bound the ordered-broadcast result below Binder limits.
    private static boolean registered;
    private static volatile Source source;

    @FunctionalInterface public interface Archives {
        ArchivePaths locate(ClassLoader loader, Context host) throws ReflectiveOperationException;
    }
    public record ArchivePaths(List<String> paths, boolean loaded) { }
    private record Source(WeakReference<ClassLoader> loader, Archives archives) { }

    enum Status {
        LOADED("已从 SystemUI 当前加载的 SDK 读取"),
        PROVISIONED("SDK 未加载，已从 SystemUI 的原厂 SDK 归档读取"),
        NOT_LOADED("SystemUI 尚未加载 Seedling SDK"),
        UNAVAILABLE("SystemUI 采集入口未就绪，请检查作用域"),
        ARCHIVE_UNAVAILABLE("SystemUI 当前加载的 SDK 归档不可读或无法识别"),
        UNSUPPORTED("当前固件的 SDK 加载接口不可用"),
        TIMED_OUT("等待 SystemUI 采集超时");

        final String label;
        Status(String label) { this.label = label; }
    }

    record Snapshot(Status status, String version, long code, int classes, String interfaces) {
        static Snapshot unavailable(Status status) { return new Snapshot(status, "", -1, 0, ""); }
        boolean loaded() { return status == Status.LOADED; }
        boolean available() { return loaded() || status == Status.PROVISIONED; }

        Bundle bundle() {
            var data = new Bundle();
            data.putString("status", status.name());
            data.putString("version", version);
            data.putLong("code", code);
            data.putInt("classes", classes);
            data.putString("interfaces", interfaces);
            return data;
        }

        static Snapshot decode(Bundle data) {
            if (data == null) return unavailable(Status.UNAVAILABLE);
            try {
                Status status = Status.valueOf(data.getString("status", ""));
                if (status != Status.LOADED && status != Status.PROVISIONED) return unavailable(status);
                String report = data.getString("interfaces", "");
                if (report.length() > MAX_REPORT_CHARS + 100) return unavailable(Status.UNSUPPORTED);
                return new Snapshot(status, data.getString("version", ""), data.getLong("code", -1),
                        data.getInt("classes", 0), report);
            } catch (IllegalArgumentException malformed) {
                return unavailable(Status.UNSUPPORTED);
            }
        }
    }

    private SeedlingSdkDiagnostics() { }

    public static synchronized void attach(Context context, ClassLoader loader, Archives archives) {
        Source previous = source;
        if (previous == null || previous.loader.get() != loader)
            source = new Source(new WeakReference<>(loader), archives);
        if (registered) return;
        Context app = context.getApplicationContext();
        if (app == null || !SYSTEM_UI.equals(app.getPackageName())) return;
        app.registerReceiver(new BroadcastReceiver() {
            @Override public void onReceive(Context receiver, Intent intent) {
                if (!ACTION.equals(intent.getAction()) || !isOrderedBroadcast()) return;
                var result = goAsync();
                boolean includeInterfaces = intent.getBooleanExtra("interfaces", false);
                DiagnosticReporter.execute(() -> {
                    try {
                        result.setResultExtras(collect(app, includeInterfaces).bundle());
                        result.setResultCode(Activity.RESULT_OK);
                    } finally { result.finish(); }
                });
            }
        }, new IntentFilter(ACTION), PERMISSION, null, Context.RECEIVER_EXPORTED);
        registered = true;
    }

    public static synchronized void detach(ClassLoader loader) {
        Source previous = source;
        if (previous != null && previous.loader.get() == loader) source = null;
    }

    static Snapshot request(Context context, boolean includeInterfaces) {
        var response = new CompletableFuture<Snapshot>();
        try {
            context.sendOrderedBroadcast(new Intent(ACTION).setPackage(SYSTEM_UI).putExtra("interfaces", includeInterfaces),
                    null, new BroadcastReceiver() {
                        @Override public void onReceive(Context app, Intent ignored) {
                            response.complete(getResultCode() == Activity.RESULT_OK ? Snapshot.decode(getResultExtras(false))
                                    : Snapshot.unavailable(Status.UNAVAILABLE));
                        }
                    }, null, Activity.RESULT_CANCELED, null, null);
            return response.get(5, TimeUnit.SECONDS);
        } catch (InterruptedException cancelled) {
            Thread.currentThread().interrupt();
            return Snapshot.unavailable(Status.UNAVAILABLE);
        } catch (TimeoutException timeout) {
            return Snapshot.unavailable(Status.TIMED_OUT);
        } catch (ExecutionException | RuntimeException unavailable) {
            return Snapshot.unavailable(Status.UNAVAILABLE);
        }
    }

    private static Snapshot collect(Context context, boolean includeInterfaces) {
        Source current = source;
        ClassLoader loader = current == null ? null : current.loader.get();
        if (loader == null) return Snapshot.unavailable(Status.NOT_LOADED);
        try {
            var source = current.archives.locate(loader, context);
            var packages = context.getPackageManager();
            for (String path : source.paths()) {
                var archive = new File(path);
                if (!archive.isFile() || !archive.canRead()) continue;
                var info = packages.getPackageArchiveInfo(path, PackageManager.PackageInfoFlags.of(0));
                if (info == null || !PACKAGE.equals(info.packageName)) continue;
                String version = info.versionName == null ? "" : info.versionName;
                if (version.length() > 160) version = version.substring(0, 160);
                return fromArchive(archive, version, info.getLongVersionCode(),
                        source.loaded() ? Status.LOADED : Status.PROVISIONED, includeInterfaces);
            }
            return Snapshot.unavailable(source.loaded() ? Status.ARCHIVE_UNAVAILABLE : Status.NOT_LOADED);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError unsupported) {
            return Snapshot.unavailable(Status.UNSUPPORTED);
        }
    }

    static Snapshot fromArchive(File archive, String version, long code, Status status, boolean includeInterfaces) {
        if (!includeInterfaces) return new Snapshot(status, version, code, 0, "");
        var writer = new InterfaceWriter();
        int classes;
        try {
            classes = DiagnosticInterfaces.writeLoadedSeedling(archive, writer);
        } catch (ReportLimit limit) {
            writer.text.append("\n[SDK interface report truncated]\n");
            classes = 1;
        } catch (IOException unavailable) {
            writer.text.append("\n[SDK interface extraction incomplete]\n");
            classes = 0;
        }
        return new Snapshot(status, version, code, classes, writer.text.toString());
    }

    private static final class ReportLimit extends IOException { }

    private static final class InterfaceWriter extends Writer {
        final StringBuilder text = new StringBuilder();

        @Override public void write(char[] chars, int start, int count) throws IOException {
            if (count > MAX_REPORT_CHARS - text.length()) throw new ReportLimit();
            text.append(chars, start, count);
        }
        @Override public void flush() { }
        @Override public void close() { }
    }
}
