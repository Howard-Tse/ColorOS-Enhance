package dev.howard.aodenhance;

import android.view.View;
import android.view.ViewGroup;
import android.content.res.Resources;
import java.util.function.BiConsumer;

final class ClockElements {
    private ClockElements() {}

    static void discover(View view, BiConsumer<View, String> register) {
        // Caller supplies a confirmed lockscreen clock root. Resource identity also excludes
        // SystemUI/notification dates, and works for old big, new digital and base clock layouts.
        String element = element(view);
        if (element != null) register.accept(view, element);
        if (view instanceof ViewGroup group) {
            for (int i = 0; i < group.getChildCount(); i++) discover(group.getChildAt(i), register);
        }
    }

    private static String element(View view) {
        if (view.getId() == View.NO_ID) return null;
        Resources resources = view.getResources();
        try {
            if (!"com.oplus.keyguard.personality.clocks".equals(resources.getResourcePackageName(view.getId())))
                return null;
            return switch (resources.getResourceEntryName(view.getId())) {
                case "date_text", "date_text_ext" -> "date";
                case "week_text" -> "week";
                case "extra_text", "extra_text_ext" -> "lunar";
                default -> null;
            };
        } catch (Resources.NotFoundException absent) {
            return null;
        }
    }
}
