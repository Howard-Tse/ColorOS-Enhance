package s7;

import android.graphics.drawable.ColorDrawable;
import y9.l;

/** Models Seedling ULE's independently sized glass drawable. */
public final class f0 extends ColorDrawable {
    private l c = new l(1159f, 307f);

    public void d(float width, float height, float x, float y) {
        c = new l(width, height);
    }

    public l shaderSize() { return c; }
}
