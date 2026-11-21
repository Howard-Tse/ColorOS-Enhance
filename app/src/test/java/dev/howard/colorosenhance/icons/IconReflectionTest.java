package dev.howard.colorosenhance.icons;

import org.junit.Test;
import static org.junit.Assert.*;

public class IconReflectionTest {
    @Test public void orderingSupportsInheritedSingleArgumentAlpha() throws Exception {
        var state = new LegacyIcon();
        var setter = IconReflection.alphaSetter(LegacyIcon.class);
        assertEquals(LegacyViewState.class, setter.getDeclaringClass());
        IconReflection.applyOrderAlpha(setter, state);
        assertEquals(1f, state.alpha, 0f);
    }

    @Test public void orderingStillPrefersTaggedAlphaWhenBothExist() throws Exception {
        var state = new TaggedIcon();
        IconReflection.applyOrderAlpha(IconReflection.alphaSetter(TaggedIcon.class), state);
        assertEquals(1f, state.alpha, 0f);
        assertEquals("ColorOS Enhance icon order", state.reason);
    }

    static class LegacyViewState {
        float alpha;
        public void setAlpha(float value) { alpha = value; }
    }

    static final class LegacyIcon extends LegacyViewState { }

    static final class TaggedIcon extends LegacyViewState {
        String reason;
        public void setAlpha(float value, String tag) { alpha = value; reason = tag; }
    }
}
