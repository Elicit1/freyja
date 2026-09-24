package com.astra.freyja.service;

import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/** Executes a native process without shell/WSL indirection and drains its output concurrently. */
@Component
public class NativeProcessExecutor {
    private static final int MAX_OUTPUT_BYTES = 64 * 1024;

    public ProcessResult execute(List<String> command, Duration timeout) throws IOException {
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        CompletableFuture<String> outputFuture = CompletableFuture.supplyAsync(() -> readOutput(process));
        try {
            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroy();
                process.waitFor(500, TimeUnit.MILLISECONDS);
                process.destroyForcibly();
                return new ProcessResult(-1, true, outputFuture.getNow("process timeout"));
            }
            return new ProcessResult(process.exitValue(), false, outputFuture.join());
        } catch (InterruptedException e) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            return new ProcessResult(-1, true, "process interrupted");
        }
    }

    private String readOutput(Process process) {
        try (var input = process.getInputStream(); var output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int total = 0;
            int read;
            while ((read = input.read(buffer)) != -1) {
                int allowed = Math.min(read, MAX_OUTPUT_BYTES - total);
                if (allowed > 0) {
                    output.write(buffer, 0, allowed);
                    total += allowed;
                }
            }
            return output.toString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "unable to read process output: " + e.getMessage();
        }
    }

    public record ProcessResult(int exitCode, boolean timedOut, String output) {}
}
