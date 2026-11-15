package com.howard.batterypercentweight.hook;

import java.util.Objects;
import java.util.function.Consumer;

@FunctionalInterface
interface HookAttempt<H> {
    H install();
}

final class OptionalHookInstaller {
    private OptionalHookInstaller() {
    }

    static <H> void install(
            HookAttempt<H> attempt,
            Consumer<H> success,
            Consumer<Throwable> failure) {
        try {
            success.accept(Objects.requireNonNull(attempt.install()));
        } catch (RuntimeException | LinkageError error) {
            failure.accept(error);
        }
    }
}
