package dev.howard.aodenhance;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.res.Resources;
import android.view.View;
import android.widget.FrameLayout;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 36)
public class ClockElementsTest {
    @Test public void discoversDateWeekAndLunarWithLegacyResourceIds() {
        Fixture fixture = new Fixture();
        View date = fixture.add(1, "date_text");
        View week = fixture.add(2, "week_text");
        View lunar = fixture.add(3, "extra_text");
        assertEquals(Map.of(date, "date", week, "week", lunar, "lunar"), fixture.discover());
    }

    @Test public void discoversExtendedDateAndLunarOn17WithoutHidingWholeClock() {
        Fixture fixture = new Fixture();
        View date = fixture.add(4, "date_text_ext");
        View lunar = fixture.add(5, "extra_text_ext");
        fixture.add(6, "clock_text");
        fixture.add(7, "tips_text");
        assertEquals(Map.of(date, "date", lunar, "lunar"), fixture.discover());
    }

    @Test public void findsBothClocksWhenResourceIdsRepeatAndNewViewsAreAdded() {
        Fixture fixture = new Fixture();
        View firstDate = fixture.add(1, "date_text");
        assertEquals(Map.of(firstDate, "date"), fixture.discover());
        FrameLayout secondClock = new FrameLayout(fixture.context);
        View secondDate = new View(fixture.context);
        secondDate.setId(1);
        secondClock.addView(secondDate);
        fixture.root.addView(secondClock);
        assertEquals(Map.of(firstDate, "date", secondDate, "date"), fixture.discover());
    }

    @Test public void excludesSystemUiDateAndViewsWithoutPluginResources() {
        Fixture fixture = new Fixture();
        fixture.add(8, "date_text");
        fixture.packages.put(8, "com.android.systemui");
        fixture.root.addView(new View(fixture.context));
        View unknown = new View(fixture.context);
        unknown.setId(123456);
        fixture.root.addView(unknown);
        assertTrue(fixture.discover().isEmpty());
    }

    private static class Fixture {
        final Map<Integer, String> names = new LinkedHashMap<>();
        final Map<Integer, String> packages = new LinkedHashMap<>();
        final Context context;
        final FrameLayout root;
        Fixture() {
            Context app = RuntimeEnvironment.getApplication();
            Resources base = app.getResources();
            Resources resources = new Resources(base.getAssets(), base.getDisplayMetrics(), base.getConfiguration()) {
                @Override public String getResourcePackageName(int id) {
                    if (!packages.containsKey(id)) throw new NotFoundException();
                    return packages.get(id);
                }
                @Override public String getResourceEntryName(int id) {
                    if (!names.containsKey(id)) throw new NotFoundException();
                    return names.get(id);
                }
            };
            context = new ContextWrapper(app) {
                @Override public Resources getResources() { return resources; }
            };
            root = new FrameLayout(context);
        }
        View add(int id, String name) {
            names.put(id, name);
            packages.put(id, "com.oplus.keyguard.personality.clocks");
            View view = new View(context);
            view.setId(id);
            root.addView(view);
            return view;
        }
        Map<View, String> discover() {
            Map<View, String> result = new LinkedHashMap<>();
            ClockElements.discover(root, result::put);
            return result;
        }
    }
}
