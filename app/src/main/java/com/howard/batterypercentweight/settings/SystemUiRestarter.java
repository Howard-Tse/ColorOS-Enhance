package com.howard.batterypercentweight.settings;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

public final class SystemUiRestarter {
    private static final int MAX_OUTPUT_BYTES = 8 * 1024;

    private final ProcessRunner runner;
    private final Executor backgroundExecutor;
    private final Executor callbackExecutor;

    public SystemUiRestarter(ProcessRunner runner, Executor executor) {
        this(runner, executor, executor);
    }

    public SystemUiRestarter(
            ProcessRunner runner,
            Executor backgroundExecutor,
            Executor callbackExecutor) {
        this.runner = Objects.requireNonNull(runner);
        this.backgroundExecutor = Objects.requireNonNull(backgroundExecutor);
        this.callbackExecutor = Objects.requireNonNull(callbackExecutor);
    }

    public static SystemUiRestarter create(
            Executor backgroundExecutor, Executor callbackExecutor) {
        return new SystemUiRestarter(
                SystemUiRestarter::runProcess, backgroundExecutor, callbackExecutor);
    }

    public void restart(Consumer<RestartResult> callback) {
        Objects.requireNonNull(callback);
        backgroundExecutor.execute(() -> {
            RestartResult result;
            try {
                ProcessOutput output = runner.run(new String[]{
                        "su", "-c", "killall com.android.systemui"
                });
                String message = output.output().trim();
                if (output.exitCode() == 0) {
                    result = new RestartResult(true, message);
                } else {
                    result = new RestartResult(
                            false,
                            message.isEmpty()
                                    ? "命令退出码：" + output.exitCode()
                                    : message);
                }
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                result = new RestartResult(false, "操作被中断");
            } catch (IOException | RuntimeException error) {
                String message = error.getMessage();
                result = new RestartResult(
                        false,
                        message == null || message.isBlank()
                                ? error.getClass().getSimpleName()
                                : message);
            }
            RestartResult deliveredResult = result;
            callbackExecutor.execute(() -> callback.accept(deliveredResult));
        });
    }

    private static ProcessOutput runProcess(String[] command)
            throws IOException, InterruptedException {
        Process process = null;
        try {
            process = new ProcessBuilder(command)
                    .redirectErrorStream(true)
                    .start();
            byte[] collected = readBounded(process.getInputStream());
            int exitCode = process.waitFor();
            return new ProcessOutput(
                    exitCode, new String(collected, StandardCharsets.UTF_8));
        } finally {
            if (process != null) {
                process.destroy();
            }
        }
    }

    private static byte[] readBounded(InputStream input) throws IOException {
        try (InputStream source = input;
             ByteArrayOutputStream collected = new ByteArrayOutputStream(MAX_OUTPUT_BYTES)) {
            byte[] buffer = new byte[1024];
            int count;
            while ((count = source.read(buffer)) != -1) {
                int remaining = MAX_OUTPUT_BYTES - collected.size();
                if (remaining > 0) {
                    collected.write(buffer, 0, Math.min(remaining, count));
                }
            }
            return collected.toByteArray();
        }
    }

    @FunctionalInterface
    public interface ProcessRunner {
        ProcessOutput run(String[] command) throws IOException, InterruptedException;
    }

    public record ProcessOutput(int exitCode, String output) {
        public ProcessOutput {
            Objects.requireNonNull(output);
        }
    }

    public record RestartResult(boolean success, String message) {
        public RestartResult {
            Objects.requireNonNull(message);
        }
    }
}
