package com.modocument.infrastructure.file.store;

import com.modocument.application.port.FileStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/** The shared FileStore contract on disk, plus what is specific to the local layout. */
class LocalFileStoreTest extends FileStoreContract {

    @TempDir
    Path root;

    private LocalFileStore store;

    @BeforeEach
    void setUp() {
        store = new LocalFileStore(root);
    }

    @Override
    protected FileStore store() {
        return store;
    }

    @Test
    void keysMapOntoTheExistingDiskLayout() throws IOException {
        // Files written before ws29 (plain paths under the storage root) are
        // read through the store unchanged — no migration step on local.
        Files.createDirectories(root.resolve("uploads"));
        Files.writeString(root.resolve("uploads/legacy_20250101000000.csv"), "old");
        Files.createDirectories(root.resolve("exports/u5"));
        Files.writeString(root.resolve("exports/u5/job2.json"), "{}");

        assertThat(read(store, "uploads/legacy_20250101000000.csv")).isEqualTo("old");
        assertThat(store.localCopy("uploads/legacy_20250101000000.csv"))
                .isEqualTo(root.toAbsolutePath().normalize().resolve("uploads/legacy_20250101000000.csv"));
        assertThat(store.list("exports/")).containsExactly("exports/u5/job2.json");

        put(store, "exports/doccreation-instance1.docx", "new");
        assertThat(Files.readString(root.resolve("exports/doccreation-instance1.docx"))).isEqualTo("new");
    }

    @Test
    void writesLeaveNoTempFilesBehind() throws IOException {
        put(store, "uploads/a.txt", "1");
        put(store, "uploads/a.txt", "2");

        try (var files = Files.list(root.resolve("uploads"))) {
            assertThat(files.map(p -> p.getFileName().toString())).containsExactly("a.txt");
        }
    }

    @Test
    void aRelativeRootResolvesAgainstTheWorkingDirectory() {
        LocalFileStore relative = new LocalFileStore(Path.of("wwwroot"));
        assertThat(relative.root()).isEqualTo(Path.of("wwwroot").toAbsolutePath().normalize());
    }
}
