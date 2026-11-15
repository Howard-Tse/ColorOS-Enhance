package com.howard.batterypercentweight.settings;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Queue;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;

public final class SystemUiRestarterTest {
    @Test public void usesFixedArgumentArrayWithoutShellConcatenation() {
        FakeRunner runner = new FakeRunner(0, "");
        SystemUiRestarter restarter = new SystemUiRestarter(runner, Runnable::run);
        AtomicReference<SystemUiRestarter.RestartResult> result = new AtomicReference<>();

        restarter.restart(result::set);

        assertArrayEquals(
                new String[]{"su", "-c", "killall com.android.systemui"},
                runner.lastCommand);
        assertTrue(result.get().success());
    }

    @Test public void reportsNonzeroExitAndStderr() {
        FakeRunner runner = new FakeRunner(1, "permission denied");
        AtomicReference<SystemUiRestarter.RestartResult> result = new AtomicReference<>();

        new SystemUiRestarter(runner, Runnable::run).restart(result::set);

        assertFalse(result.get().success());
        assertEquals("permission denied", result.get().message());
    }

    @Test public void runsCommandInBackgroundAndDeliversResultOnCallbackExecutor() {
        FakeRunner runner = new FakeRunner(0, "");
        QueuedExecutor background = new QueuedExecutor();
        QueuedExecutor callbacks = new QueuedExecutor();
        AtomicReference<SystemUiRestarter.RestartResult> result = new AtomicReference<>();

        new SystemUiRestarter(runner, background, callbacks).restart(result::set);

        assertNull(runner.lastCommand);
        assertNull(result.get());
        background.runNext();
        assertArrayEquals(
                new String[]{"su", "-c", "killall com.android.systemui"},
                runner.lastCommand);
        assertNull(result.get());
        callbacks.runNext();
        assertTrue(result.get().success());
    }

    @Test public void reportsRunnerFailureMessage() {
        AtomicReference<SystemUiRestarter.RestartResult> result = new AtomicReference<>();

        new SystemUiRestarter(command -> {
            throw new IOException("su unavailable");
        }, Runnable::run).restart(result::set);

        assertFalse(result.get().success());
        assertEquals("su unavailable", result.get().message());
    }

    private static final class FakeRunner implements SystemUiRestarter.ProcessRunner {
        private final int exitCode;
        private final String output;
        private String[] lastCommand;

        FakeRunner(int exitCode, String output) {
            this.exitCode = exitCode;
            this.output = output;
        }

        @Override public SystemUiRestarter.ProcessOutput run(String[] command) {
            lastCommand = command.clone();
            return new SystemUiRestarter.ProcessOutput(exitCode, output);
        }
    }

    private static final class QueuedExecutor implements Executor {
        private final Queue<Runnable> tasks = new ArrayDeque<>();

        @Override public void execute(Runnable command) {
            tasks.add(command);
        }

        void runNext() {
            tasks.remove().run();
        }
    }
}
