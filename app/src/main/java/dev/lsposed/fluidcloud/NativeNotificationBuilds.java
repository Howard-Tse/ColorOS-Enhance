package dev.lsposed.fluidcloud;

import io.github.libxposed.api.XposedInterface;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

/** Ask the existing OEM task queue to prepare converted items outside its twenty-item build window. */
final class NativeNotificationBuilds {
    static void install(NotificationCloudFeature feature, ClassLoader loader, NotificationPluginRepository binding,
                        Class<?> entry) throws ReflectiveOperationException {
        Class<?> repository = binding.singleton().getType();
        Class<?> wrapped = binding.wrapped();
        Class<?> scheduler = Reflect.type(loader, "com.heytap.log.config.a");
        Class<?> task = Reflect.type(loader, "com.oplus.systemui.plugins.seedling.engine.taskengine.o");
        Field candidates = Reflect.field(repository, "f", Map.class), builder = Reflect.field(repository, "m", scheduler);
        Method changed = Reflect.method(repository, "c", void.class, entry, boolean.class);
        Method key = Reflect.method(wrapped, "getKey", String.class), ready = Reflect.method(wrapped, "m", boolean.class);
        Method pending = Reflect.signature(scheduler, Map.class, entry);
        Method create = Reflect.signature(scheduler, task, entry, wrapped, int.class);
        Method enqueue = task.getMethod("b", Object.class);
        feature.module.hook(changed).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
            Object result = chain.proceed();
            String area = chain.getArg(0).toString();
            if (!feature.enabled() || !feature.unlocked()
                    || !("ENTRY_STATUS_BAR".equals(area) || feature.rules().shadeCards() && "ENTRY_NOTIFICATION".equals(area))) return result;
            Object collection = ((Map<?, ?>) Reflect.get(candidates, chain.getThisObject())).get(chain.getArg(0));
            if (!(collection instanceof List<?> data) || data.size() <= 20) return result;
            Object queue = Reflect.get(builder, chain.getThisObject());
            Map<?, ?> jobs = (Map<?, ?>) Reflect.call(pending, queue, chain.getArg(0));
            int position = 0;
            for (Object model : List.copyOf(data)) {
                if (feature.store.get((String) Reflect.call(key, model)) != null
                        && !Boolean.TRUE.equals(Reflect.call(ready, model)) && !jobs.containsKey(model))
                    Reflect.call(enqueue, Reflect.call(create, queue, chain.getArg(0), model, position), model);
                position++;
            }
            return result;
        });
    }
    private NativeNotificationBuilds() { }
}
