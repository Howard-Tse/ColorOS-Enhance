package com.howard.batterypercentweight.hook;

import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.howard.batterypercentweight.PreferenceKeys;
import com.howard.batterypercentweight.TextAdjustmentConfig;
import com.howard.batterypercentweight.TypographySettings;
import com.howard.batterypercentweight.WeightConfig;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;

import io.github.libxposed.api.XposedInterface.ExceptionMode;
import io.github.libxposed.api.XposedInterface.HookHandle;
import io.github.libxposed.api.XposedModule;

public final class BatteryHookInstaller {
    private static final String TAG = "BatteryPercentWeight";

    private final XposedModule module;
    private final ClassLoader classLoader;
    private final SharedPreferences preferences;
    private final DrawableController controller;
    private final ErrorLogger errorLogger;
    private final List<HookHandle> installedHooks = new ArrayList<>();

    private BatteryHookInstaller(
            XposedModule module,
            ClassLoader classLoader,
            SharedPreferences preferences,
            DrawableController controller,
            ErrorLogger errorLogger) {
        this.module = module;
        this.classLoader = classLoader;
        this.preferences = preferences;
        this.controller = controller;
        this.errorLogger = errorLogger;
    }

    public static BatteryHookInstaller create(
            XposedModule module,
            ClassLoader classLoader,
            SharedPreferences preferences) {
        ErrorLogger errorLogger = (message, error) ->
                safeLog(module, Log.ERROR, message, error);
        Handler mainHandler = new Handler(Looper.getMainLooper());
        DrawableRegistry registry = new DrawableRegistry();
        AndroidDrawableMutator mutator = new AndroidDrawableMutator(errorLogger);
        UiDispatcher dispatcher = action -> {
            try {
                boolean posted = mainHandler.post(() -> {
                    try {
                        action.run();
                    } catch (RuntimeException | LinkageError error) {
                        errorLogger.log("Unable to update battery drawables", error);
                    }
                });
                if (!posted) {
                    errorLogger.log(
                            "Unable to post battery drawable update to main thread",
                            new IllegalStateException("Main looper rejected update"));
                }
            } catch (RuntimeException | LinkageError error) {
                errorLogger.log("Unable to dispatch battery drawable update", error);
            }
        };
        DrawableController controller = new DrawableController(
                registry,
                mutator,
                dispatcher,
                readSettings(preferences, errorLogger),
                errorLogger);
        return new BatteryHookInstaller(
                module, classLoader, preferences, controller, errorLogger);
    }

    public void install() {
        for (String candidate : TargetClasses.CANDIDATES) {
            installCandidate(candidate);
        }
    }

    public void refreshFromPreferences() {
        try {
            controller.refresh(readSettings(preferences, errorLogger));
        } catch (RuntimeException | LinkageError error) {
            errorLogger.log("Unable to refresh battery typography preferences", error);
        }
    }

    private void installCandidate(String candidate) {
        HookTransaction<HookHandle> transaction = new HookTransaction<>(
                HookHandle::unhook,
                error -> errorLogger.log(
                        "Unable to roll back " + candidate + " hook", error));
        try {
            Class<?> targetClass = Class.forName(candidate, false, classLoader);
            Optional<Field> resolvedField = PaintFieldResolver.resolve(targetClass);
            if (resolvedField.isEmpty()) {
                rejectCandidate(
                        transaction,
                        candidate,
                        "percentage Paint not uniquely resolved",
                        null);
                return;
            }

            Field field = resolvedField.get();
            Optional<Method> resolvedBoundsUpdater =
                    MethodResolver.findBoundsUpdater(targetClass);
            if (resolvedBoundsUpdater.isEmpty()) {
                rejectCandidate(
                        transaction,
                        candidate,
                        "percentage text bounds updater not uniquely resolved",
                        null);
                return;
            }
            Method boundsUpdater = resolvedBoundsUpdater.get();

            Optional<Method> resolvedContextGetter =
                    MethodResolver.findContextGetter(targetClass);
            if (resolvedContextGetter.isEmpty()) {
                rejectCandidate(
                        transaction,
                        candidate,
                        "getContext() not uniquely resolved",
                        null);
                return;
            }
            Method contextGetter = resolvedContextGetter.get();

            Constructor<?>[] constructors = targetClass.getDeclaredConstructors();
            int usableConstructors = 0;
            for (Constructor<?> constructor : constructors) {
                if (constructor.isSynthetic()) {
                    continue;
                }
                usableConstructors++;
                try {
                    transaction.record(hookConstructor(
                            constructor, field, boundsUpdater, contextGetter));
                } catch (RuntimeException | LinkageError error) {
                    rejectCandidate(
                            transaction, candidate, "constructor hook failed", error);
                    return;
                }
            }

            if (usableConstructors == 0) {
                rejectCandidate(
                        transaction, candidate, "no usable declared constructor", null);
                return;
            }

            installedHooks.addAll(transaction.commit());

            // Prefer the full new layout contract. A dedicated measurement helper alone
            // does not rule out a firmware that still uses the legacy margin accessor.
            Optional<Method> percentDrawer = MethodResolver.findPercentContentDrawer(targetClass);
            boolean modernLayout = percentDrawer.isPresent()
                    || (boundsUpdater.getName().equals("updatePercentInTextBounds")
                    && MethodResolver.findTextMarginTopAccessor(targetClass).isEmpty());
            if (!modernLayout || MethodResolver.findFontVariationSetter(targetClass).isPresent()) {
                installOptionalHook(
                        candidate,
                        "setFontVar(String)",
                        () -> hookFontVariation(
                                MethodResolver.findFontVariationSetter(targetClass)
                                        .orElseThrow(() -> new IllegalStateException(
                                                "setFontVar(String) unavailable")),
                                field));
            }
            installOptionalHook(
                    candidate,
                    "setFontTypeface",
                    () -> hookFontTypeface(
                            MethodResolver.findTypefaceSetter(targetClass)
                                    .orElseThrow(() -> new IllegalStateException(
                                            "setFontTypeface callback unavailable")),
                            field));
            if (modernLayout) {
                installOptionalHook(candidate, "percentage drawContent(Canvas, RectF)",
                        () -> hookPercentContentDrawing(
                                percentDrawer
                                        .orElseThrow(() -> new IllegalStateException(
                                                "percentage content layout contract unavailable"))));
            } else {
                installOptionalHook(
                        candidate,
                        "getOffsetX()",
                        () -> hookPositionAccessor(
                                MethodResolver.findOffsetXAccessor(targetClass)
                                        .orElseThrow(() -> new IllegalStateException(
                                                "getOffsetX() unavailable")),
                                true));
                installOptionalHook(
                        candidate,
                        "getTextMarginTop()",
                        () -> hookPositionAccessor(
                                MethodResolver.findTextMarginTopAccessor(targetClass)
                                        .orElseThrow(() -> new IllegalStateException(
                                                "getTextMarginTop() unavailable")),
                                false));
            }

            safeLog(module, Log.INFO, "Installed " + candidate, null);
        } catch (ClassNotFoundException error) {
            rejectCandidate(transaction, candidate, "class unavailable", null);
        } catch (RuntimeException | LinkageError error) {
            rejectCandidate(transaction, candidate, "adapter probe failed", error);
        }
    }

    private HookHandle hookConstructor(
            Constructor<?> constructor,
            Field field,
            Method boundsUpdater,
            Method contextGetter) {
        return module.hook(constructor)
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .intercept(chain -> {
                    Object result = chain.proceed();
                    try {
                        controller.onConstructed(
                                chain.getThisObject(),
                                field,
                                boundsUpdater,
                                contextGetter);
                    } catch (RuntimeException | LinkageError error) {
                        errorLogger.log("Constructor callback failed", error);
                    }
                    return result;
                });
    }

    private HookHandle hookFontVariation(Method method, Field field) {
        return module.hook(method)
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .intercept(chain -> {
                    Object result = chain.proceed();
                    try {
                        Object argument = chain.getArg(0);
                        String originalVariation =
                                argument instanceof String value ? value : null;
                        controller.afterSystemFontChanged(
                                chain.getThisObject(), field, originalVariation);
                    } catch (RuntimeException | LinkageError error) {
                        errorLogger.log("setFontVar callback failed", error);
                    }
                    return result;
                });
    }

    private HookHandle hookFontTypeface(Method method, Field field) {
        return module.hook(method)
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .intercept(chain -> {
                    Object result = chain.proceed();
                    try {
                        controller.afterSystemFontChanged(
                                chain.getThisObject(), field, null);
                    } catch (RuntimeException | LinkageError error) {
                        errorLogger.log("setFontTypeface callback failed", error);
                    }
                    return result;
                });
    }

    private HookHandle hookPositionAccessor(Method method, boolean horizontal) {
        return module.hook(method)
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .intercept(chain -> {
                    Object original = chain.proceed();
                    try {
                        if (!(original instanceof Number value)) {
                            return original;
                        }
                        return horizontal
                                ? controller.adjustHorizontal(
                                        chain.getThisObject(), value.floatValue())
                                : controller.adjustVertical(
                                        chain.getThisObject(), value.floatValue());
                    } catch (RuntimeException | LinkageError error) {
                        errorLogger.log(
                                horizontal
                                        ? "getOffsetX callback failed"
                                        : "getTextMarginTop callback failed",
                                error);
                        return original;
                    }
                });
    }

    private HookHandle hookPercentContentDrawing(Method method) {
        return module.hook(method)
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .intercept(chain -> {
                    if (!(chain.getArg(0) instanceof Canvas canvas)) {
                        return chain.proceed();
                    }
                    float dx;
                    float dy;
                    try {
                        // Legacy X adjusts a subtracted accessor; Canvas uses screen coordinates.
                        dx = -controller.adjustHorizontal(chain.getThisObject(), 0.0f);
                        dy = controller.adjustVertical(chain.getThisObject(), 0.0f);
                    } catch (RuntimeException | LinkageError error) {
                        errorLogger.log("Unable to resolve percentage content offset", error);
                        return chain.proceed();
                    }
                    return PercentContentDrawing.draw(canvas, dx, dy, chain::proceed);
                });
    }

    private void installOptionalHook(
            String candidate, String capability, HookAttempt<HookHandle> attempt) {
        OptionalHookInstaller.install(
                attempt,
                installedHooks::add,
                error -> logUnsupported(
                        candidate, capability + " hook unavailable", error));
    }

    private void rejectCandidate(
            HookTransaction<HookHandle> transaction,
            String candidate,
            String reason,
            Throwable error) {
        transaction.rollback();
        logUnsupported(candidate, reason, error);
    }

    private void logUnsupported(String candidate, String reason, Throwable error) {
        String errorType = error == null
                ? ""
                : " (" + error.getClass().getSimpleName() + ")";
        safeLog(
                module,
                Log.WARN,
                "Unsupported " + candidate + ": " + reason + errorType,
                null);
    }

    private static TypographySettings readSettings(
            SharedPreferences preferences, ErrorLogger errorLogger) {
        try {
            return new TypographySettings(
                    preferences.getBoolean(PreferenceKeys.ENABLED, false),
                    preferences.getBoolean(PreferenceKeys.WEIGHT_ENABLED, false),
                    preferences.getInt(PreferenceKeys.WEIGHT, WeightConfig.DEFAULT),
                    preferences.getBoolean(PreferenceKeys.TEXT_SIZE_ENABLED, false),
                    preferences.getInt(
                            PreferenceKeys.TEXT_SIZE_TENTHS_DP,
                            TextAdjustmentConfig.TEXT_SIZE_DEFAULT),
                    preferences.getBoolean(
                            PreferenceKeys.HORIZONTAL_OFFSET_ENABLED, false),
                    preferences.getInt(
                            PreferenceKeys.HORIZONTAL_OFFSET_TENTHS_DP,
                            TextAdjustmentConfig.OFFSET_DEFAULT),
                    preferences.getBoolean(
                            PreferenceKeys.VERTICAL_OFFSET_ENABLED, false),
                    preferences.getInt(
                            PreferenceKeys.VERTICAL_OFFSET_TENTHS_DP,
                            TextAdjustmentConfig.OFFSET_DEFAULT));
        } catch (RuntimeException | LinkageError error) {
            errorLogger.log("Unable to read battery typography preferences", error);
            return TypographySettings.defaults();
        }
    }

    private static void safeLog(
            XposedModule module, int priority, String message, Throwable error) {
        try {
            if (error == null) {
                module.log(priority, TAG, message);
            } else {
                module.log(priority, TAG, message, error);
            }
        } catch (RuntimeException | LinkageError ignored) {
            // Logging must never destabilize SystemUI.
        }
    }
}

@FunctionalInterface
interface RollbackAction<H> {
    void rollback(H handle);
}

final class HookTransaction<H> {
    private final List<H> handles = new ArrayList<>();
    private final RollbackAction<H> rollbackAction;
    private final Consumer<Throwable> failureLogger;

    HookTransaction(RollbackAction<H> rollbackAction, Consumer<Throwable> failureLogger) {
        this.rollbackAction = rollbackAction;
        this.failureLogger = failureLogger;
    }

    void record(H handle) {
        handles.add(Objects.requireNonNull(handle));
    }

    List<H> commit() {
        List<H> retained = List.copyOf(handles);
        handles.clear();
        return retained;
    }

    void rollback() {
        for (int index = handles.size() - 1; index >= 0; index--) {
            try {
                rollbackAction.rollback(handles.get(index));
            } catch (RuntimeException | LinkageError error) {
                try {
                    failureLogger.accept(error);
                } catch (RuntimeException | LinkageError ignored) {
                    // Continue rolling back the remaining hooks.
                }
            }
        }
        handles.clear();
    }
}
