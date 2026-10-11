package com.modocument.infrastructure.file.store;

import com.modocument.application.port.FileStore;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The behaviour every {@link FileStore} must share (ws29, contracts §7):
 * {@link LocalFileStoreTest} runs it on disk in the default suite, and
 * {@code S3FileStoreS3IT} runs the SAME assertions against MinIO in the
 * {@code -Ps3-it} profile (CI), so local and S3 cannot drift apart.
 */
public abstract class FileStoreContract {

    protected abstract FileStore store();

    protected static void put(FileStore store, String key, String text) throws IOException {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        store.put(key, new ByteArrayInputStream(bytes), bytes.length, "text/plain");
    }

    protected static String read(FileStore store, String key) throws IOException {
        try (InputStream in = store.open(key)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void putThenOpenExistsAndLocalCopyReturnTheBytes() throws IOException {
        FileStore store = store();
        put(store, "uploads/report_20261011120000.csv", "a,b\n1,2\n");

        assertThat(store.exists("uploads/report_20261011120000.csv")).isTrue();
        assertThat(read(store, "uploads/report_20261011120000.csv")).isEqualTo("a,b\n1,2\n");
        Path copy = store.localCopy("uploads/report_20261011120000.csv");
        assertThat(Files.readString(copy)).isEqualTo("a,b\n1,2\n");
        // Parsers sniff the extension: the local copy keeps the key's file name.
        assertThat(copy.getFileName().toString()).isEqualTo("report_20261011120000.csv");
    }

    @Test
    void putReplacesAnExistingObjectAndLocalCopyIsNeverStale() throws IOException {
        FileStore store = store();
        put(store, "exports/doccreation-instance7.docx", "first");
        assertThat(Files.readString(store.localCopy("exports/doccreation-instance7.docx"))).isEqualTo("first");

        put(store, "exports/doccreation-instance7.docx", "second, longer");

        assertThat(read(store, "exports/doccreation-instance7.docx")).isEqualTo("second, longer");
        assertThat(Files.readString(store.localCopy("exports/doccreation-instance7.docx")))
                .isEqualTo("second, longer");
    }

    @Test
    void missingKeysAreNoSuchFileAndDeletingThemIsNotAnError() throws IOException {
        FileStore store = store();

        assertThat(store.exists("uploads/nope.txt")).isFalse();
        assertThatThrownBy(() -> store.open("uploads/nope.txt")).isInstanceOf(NoSuchFileException.class);
        assertThatThrownBy(() -> store.localCopy("uploads/nope.txt")).isInstanceOf(NoSuchFileException.class);
        store.delete("uploads/nope.txt");
    }

    @Test
    void deleteRemovesTheObject() throws IOException {
        FileStore store = store();
        put(store, "exports/u3/job9.csv", "x");

        store.delete("exports/u3/job9.csv");

        assertThat(store.exists("exports/u3/job9.csv")).isFalse();
        assertThatThrownBy(() -> store.localCopy("exports/u3/job9.csv")).isInstanceOf(NoSuchFileException.class);
    }

    @Test
    void listReturnsTheKeysUnderAPrefixSorted() throws IOException {
        FileStore store = store();
        put(store, "exports/u3/job9.xlsx", "x");
        put(store, "exports/u3/job9.csv", "x");
        put(store, "exports/u31/job1.csv", "x");
        put(store, "exports/doccreation-instance1.pdf", "x");
        put(store, "uploads/a.csv", "x");

        assertThat(store.list("exports/u3/")).containsExactly("exports/u3/job9.csv", "exports/u3/job9.xlsx");
        assertThat(store.list("exports/u3")).containsExactly(
                "exports/u3/job9.csv", "exports/u3/job9.xlsx", "exports/u31/job1.csv");
        assertThat(store.list("uploads/")).containsExactly("uploads/a.csv");
        assertThat(store.list("nothing-here/")).isEmpty();
    }

    @Test
    void unsafeKeysAreRejectedByEveryOperation() {
        FileStore store = store();
        for (String key : new String[] {"/etc/passwd", "../secrets.yml", "uploads/../../x",
                "uploads\\evil.txt", "C:/Windows/win.ini", "uploads//x", "uploads/./x", ""}) {
            assertThatThrownBy(() -> put(store, key, "x")).as("put %s", key)
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
        assertThatThrownBy(() -> store.list("../")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void binaryContentRoundTripsUnchanged() throws IOException {
        FileStore store = store();
        byte[] bytes = new byte[256 * 1024 + 17];
        for (int i = 0; i < bytes.length; i++) {
            bytes[i] = (byte) (i * 31);
        }
        store.put("uploads/blob.bin", new ByteArrayInputStream(bytes), bytes.length, null);

        try (InputStream in = store.open("uploads/blob.bin")) {
            assertThat(in.readAllBytes()).isEqualTo(bytes);
        }
        assertThat(Files.readAllBytes(store.localCopy("uploads/blob.bin"))).isEqualTo(bytes);
    }
}
