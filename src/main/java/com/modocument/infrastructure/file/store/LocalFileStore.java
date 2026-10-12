package com.modocument.infrastructure.file.store;

import com.modocument.application.port.FileStore;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.stream.Stream;

/**
 * The default {@link FileStore}: files under a root directory
 * ({@code modocument.storage.root}, env MODOCUMENT_STORAGE_ROOT, default
 * {@code wwwroot}) in exactly the layout the disk storage always used, so
 * existing deployments keep their files. Writes go to a temp file in the
 * target folder and are moved into place atomically: a reader never sees a
 * half-written file, and an overwrite (a regenerated document) replaces the
 * old bytes in one step.
 */
public class LocalFileStore implements FileStore {

    /** Suffix of in-flight writes; never listed. */
    static final String TEMP_SUFFIX = ".fstmp";

    private final Path root;

    public LocalFileStore(Path root) {
        this.root = root.toAbsolutePath().normalize();
    }

    public Path root() {
        return root;
    }

    @Override
    public void put(String key, InputStream content, long length, String contentType) throws IOException {
        Path target = resolve(key);
        Files.createDirectories(target.getParent());
        Path temp = Files.createTempFile(target.getParent(), "." + target.getFileName(), TEMP_SUFFIX);
        try {
            Files.copy(content, temp, StandardCopyOption.REPLACE_EXISTING);
            try {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    @Override
    public InputStream open(String key) throws IOException {
        return Files.newInputStream(resolve(key));
    }

    @Override
    public Path localCopy(String key) throws IOException {
        Path path = resolve(key);
        if (!Files.isRegularFile(path)) {
            throw new NoSuchFileException(key);
        }
        return path;
    }

    @Override
    public boolean exists(String key) {
        return Files.isRegularFile(resolve(key));
    }

    @Override
    public void delete(String key) throws IOException {
        Files.deleteIfExists(resolve(key));
    }

    @Override
    public List<String> list(String prefix) throws IOException {
        String safePrefix = FileStoreKeys.validatePrefix(prefix);
        int slash = safePrefix.lastIndexOf('/');
        Path dir = slash < 0 ? root : resolveDir(safePrefix.substring(0, slash));
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> files = Files.walk(dir)) {
            return files.filter(Files::isRegularFile)
                    .filter(p -> !p.getFileName().toString().endsWith(TEMP_SUFFIX))
                    .map(p -> root.relativize(p).toString().replace('\\', '/'))
                    .filter(k -> k.startsWith(safePrefix))
                    .sorted()
                    .toList();
        }
    }

    /** The file behind a key; confined to the root (defence in depth on top of key validation). */
    Path resolve(String key) {
        return resolveDir(FileStoreKeys.validate(key));
    }

    private Path resolveDir(String relative) {
        Path candidate = root.resolve(relative).normalize();
        if (!candidate.startsWith(root) || candidate.equals(root)) {
            throw new IllegalArgumentException("Invalid storage key: " + relative);
        }
        return candidate;
    }
}
