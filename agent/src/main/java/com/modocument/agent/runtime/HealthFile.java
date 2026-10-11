package com.modocument.agent.runtime;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Liveness without an inbound port: every successful poll and heartbeat
 * writes the current time to {@code <stateDir>/health}; the container
 * HEALTHCHECK ({@code java -jar agent.jar healthcheck}) is healthy while
 * that file is younger than {@link #MAX_AGE_MILLIS}.
 */
public final class HealthFile {

    static final String FILE = "health";
    public static final long MAX_AGE_MILLIS = 120_000;

    private HealthFile() {
    }

    public static void touch(Path stateDir) {
        try {
            Files.createDirectories(stateDir);
            Path temp = stateDir.resolve(FILE + ".tmp");
            Files.writeString(temp, Long.toString(System.currentTimeMillis()), StandardCharsets.US_ASCII);
            Files.move(temp, stateDir.resolve(FILE), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            // Health reporting must never stop the agent.
        }
    }

    public static boolean isFresh(Path stateDir) {
        try {
            long at = Long.parseLong(Files.readString(stateDir.resolve(FILE), StandardCharsets.US_ASCII).strip());
            return System.currentTimeMillis() - at <= MAX_AGE_MILLIS;
        } catch (IOException | NumberFormatException e) {
            return false;
        }
    }
}
