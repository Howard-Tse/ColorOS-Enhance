package dev.howard.colorosenhance.icons;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

final class IconReflection {
    private IconReflection() {}
    static Object field(Object object, String name) throws ReflectiveOperationException {
        for (Class<?> type = object.getClass(); type != null; type = type.getSuperclass()) {
            try { Field field = type.getDeclaredField(name); field.setAccessible(true); return field.get(object); }
            catch (NoSuchFieldException absent) { /* Search base classes. */ }
        }
        throw new NoSuchFieldException(name);
    }
    static Method method(Class<?> type, String name, Class<?>... args) throws NoSuchMethodException {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            try { Method method = current.getDeclaredMethod(name, args); method.setAccessible(true); return method; }
            catch (NoSuchMethodException absent) { /* Search base classes. */ }
        }
        // Some pipeline views inherit their implementation from StatusIconDisplayable.
        // getDeclaredMethod on the class hierarchy does not see interface defaults.
        Method method = type.getMethod(name, args);
        method.setAccessible(true);
        return method;
    }
    static Method optional(Class<?> type, String name, Class<?>... args) {
        try { return method(type, name, args); } catch (NoSuchMethodException absent) { return null; }
    }
    static Method alphaSetter(Class<?> type) throws NoSuchMethodException {
        Method tagged = optional(type, "setAlpha", float.class, String.class);
        Method setter = tagged != null ? tagged : method(type, "setAlpha", float.class);
        if (setter.getReturnType() != void.class || java.lang.reflect.Modifier.isStatic(setter.getModifiers()))
            throw new NoSuchMethodException(type.getName() + ".setAlpha");
        return setter;
    }
    static void applyOrderAlpha(Method setter, Object state) throws ReflectiveOperationException {
        if (setter.getParameterCount() == 2) setter.invoke(state, 1f, "ColorOS Enhance icon order");
        else setter.invoke(state, 1f);
    }
    static Object call(Object object, String name) throws ReflectiveOperationException {
        return method(object.getClass(), name).invoke(object);
    }
}
