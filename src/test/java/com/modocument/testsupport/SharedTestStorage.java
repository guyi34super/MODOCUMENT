package com.modocument.testsupport;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;

/**
 * Storage roots for SHARED Spring test contexts. A JUnit {@code @TempDir}
 * is deleted when its test class finishes, but a cached context outlives
 * the class and is reused by the next class with the same configuration —
 * so a context shared by several classes needs a directory that lives as
 * long as the test JVM. Created once per name, removed at JVM exit.
 */
public final class SharedTestStorage {

    private SharedTestStorage() {
    }

    public static Path directory(String name) {
        try {
            Path dir = Files.createTempDirectory("modoc-test-" + name + "-");
            Runtime.getRuntime().addShutdownHook(new Thread(() -> deleteTree(dir),
                    "delete-" + name + "-storage"));
            return dir;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void deleteTree(Path root) {
        try (Stream<Path> paths = Files.walk(root)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // best effort at JVM exit
                }
            });
        } catch (IOException ignored) {
            // best effort at JVM exit
        }
    }
}
