package dev.howard.colorosenhance.diagnostics;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.widget.Toast;
import dev.howard.colorosenhance.BuildConfig;

/** Launch the app-owned screen without loading its UI classes in Settings. */
public final class DiagnosticLinks {
    private DiagnosticLinks() { }

    public static void open(Context context) {
        Intent intent = new Intent().setClassName(BuildConfig.PUBLIC_APPLICATION_ID,
                "dev.howard.colorosenhance.diagnostics.DiagnosticsActivity");
        if (!(context instanceof Activity)) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try { context.startActivity(intent); }
        catch (RuntimeException unavailable) {
            Toast.makeText(context, "暂时无法打开模块状态", Toast.LENGTH_SHORT).show();
        }
    }
}
