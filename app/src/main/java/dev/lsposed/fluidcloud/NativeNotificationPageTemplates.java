package dev.lsposed.fluidcloud;

import android.os.Bundle;
import android.view.View;
import android.widget.FrameLayout;
import io.github.libxposed.api.XposedInterface;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/** Builds adjacent pages with the same template factory, theme and action dispatcher as this native row. */
final class NativeNotificationPageTemplates {
    private record Recipe(Object factory, Object style, Bundle args) { }
    record Template(Object model, Object wrapper, Object template, View view) { }
    private final Map<Object, Recipe> recipes = Collections.synchronizedMap(new WeakHashMap<>());
    private final Method create, root, launch, configure, dispose, rebind;
    private final Field originalTemplate, size, callback, configuration;
    private final Constructor<?> wrapper;
    private final XposedInterface.HookHandle capture;

    NativeNotificationPageTemplates(NotificationCloudFeature feature, ClassLoader loader) throws ReflectiveOperationException {
        String prefix = "com.oplus.systemui.plugins.";
        Class<?> data = Reflect.type(loader, prefix + "shared.api.d");
        Class<?> style = Reflect.type(loader, prefix + "shared.api.LiveAlertStyle");
        Class<?> template = Reflect.type(loader, prefix + "shared.template.wrapper.impl.u");
        create = Reflect.signature(Reflect.type(loader, "com.heytap.log.appender.a"), template, style, data, Bundle.class);
        root = Reflect.method(template, "k", FrameLayout.class);
        rebind = Reflect.method(template, "o", void.class, data);
        dispose = Reflect.method(template, "h", void.class);
        Class<?> type = Reflect.type(loader, "com.oplus.systemui.livealert.notification.origin.e");
        Class<?> model = Reflect.type(loader, "com.oplus.systemui.livealert.notification.origin.d");
        Class<?> viewSize = Reflect.type(loader, prefix + "livealert.lib.ViewSize");
        Class<?> listener = Reflect.type(loader, prefix + "livealert.lib.i");
        wrapper = type.getDeclaredConstructor(model, template, viewSize);
        originalTemplate = Reflect.field(type, "c", template);
        size = Reflect.field(type, "d", viewSize);
        callback = Reflect.field(type, "e", listener);
        configuration = Reflect.field(type, "f", Bundle.class);
        launch = Reflect.method(type, "m", void.class, listener);
        configure = Reflect.method(type, "p", void.class, Bundle.class);
        capture = feature.module.hook(create).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
            Object result = chain.proceed();
            if (result != null && feature.enabled()
                    && "NOTIFICATION_CARD".equals(((Enum<?>) chain.getArg(0)).name()))
                recipes.put(result, new Recipe(chain.getThisObject(), chain.getArg(0), new Bundle((Bundle) chain.getArg(2))));
            return result;
        });
    }
    Object prototype(List<?> wrappers, View actual) {
        for (Object candidate : wrappers) {
            Object template = Reflect.get(originalTemplate, candidate);
            if (recipes.containsKey(template) && Reflect.call(root, template) == actual) return candidate;
        }
        return null;
    }
    Template inflate(Object prototype, Object model, Object data) throws ReflectiveOperationException {
        Recipe recipe = recipes.get(Reflect.get(originalTemplate, prototype));
        if (recipe == null) throw new IllegalStateException("Missing native page template");
        Object template = Reflect.call(create, recipe.factory(), recipe.style(), data, new Bundle(recipe.args()));
        if (template == null) throw new IllegalStateException("Empty native page template");
        try {
            Object bound = wrapper.newInstance(model, template, Reflect.get(size, prototype));
            Reflect.call(launch, bound, Reflect.get(callback, prototype));
            Reflect.call(configure, bound, new Bundle((Bundle) Reflect.get(configuration, prototype)));
            return new Template(model, bound, template, (View) Reflect.call(root, template));
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
            Reflect.call(dispose, template);
            throw failure;
        }
    }
    void update(Template page, Object data) { Reflect.call(rebind, page.template(), data); }
    void release(Template page) {
        Reflect.call(launch, page.wrapper(), new Object[]{null});
        Reflect.call(dispose, page.template());
    }
    void close() { capture.unhook(); recipes.clear(); }
}
