package dev.lsposed.fluidcloud;

import android.view.View;

/** The second ColorOS 16 Seedling layout. Select it by typed plugin members, never by an OTA string. */
final class ColorOs16Plugin {
    static final String CARD_ROOT = "u4.d";
    static final String CARD_WINDOW = "x4.h";
    static final String CARD_OUTLINE = "t5.i";
    static final String CAPSULE_MODEL = "m4.q";
    static final String CAPSULE_STATE = "h4.e";

    static boolean capsule(Class<?> container) {
        try {
            return "m4.q".equals(container.getDeclaredField("w").getType().getName())
                    && CAPSULE_STATE.equals(container.getDeclaredField("v").getType().getName());
        } catch (NoSuchFieldException missing) { return false; }
    }

    static boolean cards(Class<?> card) {
        try {
            return card.getDeclaredField("r").getType() == View.class
                    && PluginClasses.has(card, void.class, Reflect.type(card.getClassLoader(), "t4.o"));
        } catch (ReflectiveOperationException missing) { return false; }
    }

    static Object state(Class<?> type, String name) throws NoSuchFieldException {
        for (Object value : type.getEnumConstants())
            if (((Enum<?>) value).name().equals(name)) return value;
        throw new NoSuchFieldException(type.getName() + "." + name);
    }

    private ColorOs16Plugin() { }
}
