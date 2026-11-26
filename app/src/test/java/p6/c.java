package p6;

import android.graphics.drawable.ColorDrawable;
import y9.l;

/** Models the light drawable used by Seedling's RemoteViews renderer. */
public final class c extends ColorDrawable {
    private l c = new l(1159f, 307f);

    public void d(float width, float height, float x, float y) {
        c = new l(width, height);
    }

    public l shaderSize() { return c; }
}
