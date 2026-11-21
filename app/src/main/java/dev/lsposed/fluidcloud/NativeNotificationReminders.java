package dev.lsposed.fluidcloud;

import io.github.libxposed.api.XposedInterface;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
import org.json.JSONException;
import org.json.JSONObject;

/** Marks converted notifications for ColorOS's normal automatic card presentation. */
final class NativeNotificationReminders {
    private static final int TIMED_REMINDER = 11;
    private static final long NATIVE_DURATION_MS = 3000L;
    private static final int CARD_REMINDER = 2;
    private static final int CARD_DURATION = 4;
    private static final int CARD_ACTION = 10;
    private record Request(String sourceKey, long duration, JSONObject previousMetadata) { }

    private final Map<Object, Request> requests = Collections.synchronizedMap(new IdentityHashMap<>());
    private final Field metadata, revision;
    private final Method remindType, modelRevision;
    private XposedInterface.HookHandle durationHook;

    NativeNotificationReminders(Class<?> model, Field metadata) throws ReflectiveOperationException {
        this.metadata = metadata;
        revision = Reflect.field(model, "g", long.class);
        remindType = Reflect.method(model, "d", int.class);
        modelRevision = Reflect.method(model, "u", long.class);
    }

    void installDuration(NotificationCloudFeature feature, ClassLoader loader) throws ReflectiveOperationException {
        String plugin = "com.oplus.systemui.plugins.";
        Class<?> card = Reflect.type(loader, plugin + "seedling.card.data.model.a");
        Class<?> action = Reflect.type(loader, plugin + "seedling.capsule.data.repo.c");
        Class<?> wrapped = Reflect.type(loader, plugin + "seedling.data.interactor.m");
        Field actionModel = Reflect.field(action, "a", wrapped);
        Field sourceModel = Reflect.field(wrapped, "a", Reflect.type(loader, plugin + "livealert.lib.m"));
        Constructor<?> constructor = null;
        for (Constructor<?> candidate : card.getDeclaredConstructors()) {
            Class<?>[] parameters = candidate.getParameterTypes();
            if (parameters.length <= CARD_ACTION || parameters[0] != String.class
                    || parameters[CARD_REMINDER] != boolean.class || parameters[CARD_DURATION] != long.class
                    || parameters[CARD_ACTION] != action) continue;
            if (constructor != null) throw new NoSuchMethodException("Ambiguous native card model constructor");
            constructor = candidate;
        }
        if (constructor == null) throw new NoSuchMethodException("Native card model constructor");
        durationHook = feature.module.hook(constructor).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(chain -> {
            if (requests.isEmpty() || !Boolean.TRUE.equals(chain.getArg(CARD_REMINDER))
                    || !Long.valueOf(NATIVE_DURATION_MS).equals(chain.getArg(CARD_DURATION))) return chain.proceed();
            Object source = Reflect.get(sourceModel, Reflect.get(actionModel, chain.getArg(CARD_ACTION)));
            Request request = requests.get(source);
            if (request == null || request.duration() == NATIVE_DURATION_MS) return chain.proceed();
            Object[] args = chain.getArgs().toArray();
            args[CARD_DURATION] = request.duration();
            return chain.proceed(args);
        });
    }

    boolean update(Object model, String sourceKey, long postedAt, boolean autoExpand, Object seconds)
            throws IllegalAccessException {
        Request request = requests.get(model);
        if (!autoExpand) {
            if (request == null || sourceKey.equals(request.sourceKey())) return false;
            metadata.set(model, request.previousMetadata());
            requests.remove(model);
            return true;
        }

        JSONObject original = request == null ? (JSONObject) metadata.get(model) : request.previousMetadata();
        JSONObject current = (JSONObject) metadata.get(model);
        long previous = revision.getLong(model);
        if (!Long.valueOf(previous).equals(Reflect.call(modelRevision, model))) return false;
        try {
            JSONObject flagged = original == null ? new JSONObject() : new JSONObject(original.toString());
            flagged.put("remindType", TIMED_REMINDER);
            metadata.set(model, flagged);
            long next = Math.max(previous + 1, postedAt);
            revision.setLong(model, next);
            // The repository uses this revision to re-arm an existing grouped candidate.
            if (!Integer.valueOf(TIMED_REMINDER).equals(Reflect.call(remindType, model))
                    || !Long.valueOf(next).equals(Reflect.call(modelRevision, model))) {
                metadata.set(model, current);
                revision.setLong(model, previous);
                return false;
            }
            requests.put(model, new Request(sourceKey, durationMillis(seconds), original));
            return true;
        } catch (JSONException invalid) {
            return false;
        }
    }

    static long durationMillis(Object value) {
        return value instanceof Number seconds ? Math.max(1, Math.min(15, seconds.intValue())) * 1000L : NATIVE_DURATION_MS;
    }

    void forget(Object model) { requests.remove(model); }

    void close() {
        requests.clear();
        if (durationHook != null) durationHook.unhook();
    }
}
