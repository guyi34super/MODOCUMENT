package com.modocument.infrastructure.file.store;

import com.modocument.application.port.FileStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * QA, optimization round, test plan F1 (contracts §7): the key shapes of the
 * plan that the shared {@code FileStoreContract} does not list — a NUL byte
 * and a Windows drive with a backslash through every operation, and the
 * URL-encoded traversal {@code %2e%2e} — never touch anything outside the
 * store's root. {@code %2e%2e} is not decoded by the store (keys are never
 * URL-decoded), so it is a literal, harmless directory name INSIDE the
 * root; the test proves exactly that rather than demanding a refusal.
 */
class FileStoreTraversalQaTest {

    @TempDir
    Path sandbox;

    private Path root;
    private FileStore store;

    @BeforeEach
    void setUp() throws IOException {
        root = Files.createDirectories(sandbox.resolve("root"));
        store = new LocalFileStore(root);
    }

    private void put(String key) throws IOException {
        byte[] bytes = "x".getBytes();
        store.put(key, new ByteArrayInputStream(bytes), bytes.length, "text/plain");
    }

    @Test
    void nulAndDriveKeysAreRejectedByEveryOperation() {
        for (String key : List.of("uploads/a\0.csv", "uploads/a.csv\0../../x", "C:\\x",
                "c:/x", "\\\\server\\share\\x", "uploads/..\\..\\x")) {
            assertThatThrownBy(() -> put(key)).as("put %s", key)
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> store.open(key)).as("open %s", key)
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> store.localCopy(key)).as("localCopy %s", key)
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> store.exists(key)).as("exists %s", key)
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> store.delete(key)).as("delete %s", key)
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void urlEncodedTraversalStaysALiteralNameInsideTheRoot() throws IOException {
        Path outside = sandbox.resolve("victim.txt");
        Files.writeString(outside, "keep");
        for (String key : List.of("%2e%2e/victim.txt", "uploads/%2e%2e/%2e%2e/victim.txt",
                "uploads/..%2fvictim.txt", "uploads/%2E%2E%2F%2E%2E%2Fvictim.txt")) {
            put(key);
            assertThat(store.exists(key)).as(key).isTrue();
            Path written = store.localCopy(key).toAbsolutePath().normalize();
            assertThat(written).as("%s resolves inside the root", key)
                    .startsWith(root.toAbsolutePath().normalize());
            store.delete(key);
        }
        assertThat(Files.readString(outside)).as("nothing outside the root was written").isEqualTo("keep");
        try (Stream<Path> files = Files.list(sandbox)) {
            assertThat(files.map(p -> p.getFileName().toString())).containsExactlyInAnyOrder("root",
                    "victim.txt");
        }
    }

    @Test
    void listingRefusesTraversalPrefixes() {
        for (String prefix : List.of("..", "../", "uploads/../..", "/etc", "uploads\\")) {
            assertThatThrownBy(() -> store.list(prefix)).as("list %s", prefix)
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }
}
