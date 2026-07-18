package com.kafka.producer.chaos.controller;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

final class CommandExecutor {
    private CommandExecutor() {}

    static String run(List<String> command, Duration timeout) throws Exception {
        Process process = new ProcessBuilder(command)
                .redirectErrorStream(true)
                .start();
        boolean completed = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
        if (!completed) {
            process.destroyForcibly();
            throw new IOException("Command timed out: " + String.join(" ", command));
        }
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
        if (process.exitValue() != 0) {
            throw new IOException(
                    "Command failed with exit " + process.exitValue() + ": "
                            + String.join(" ", command) + "\n" + output);
        }
        return output;
    }

    static String runShell(String command, Duration timeout) throws Exception {
        return run(List.of("sh", "-c", command), timeout);
    }
}
