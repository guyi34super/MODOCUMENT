package com.modocument.infrastructure.file.store;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * The bounded temp cache behind {@code S3FileStore.localCopy}: libraries
 * that need a {@link Path} (POI, PDFBox, LibreOffice) get a local file
 * without the store downloading the same object for every call.
 *
 * <ul>
 *   <li>One private directory per store instance under
 *       {@code java.io.tmpdir} (owner-only where POSIX), removed on close.</li>
 *   <li>Each entry remembers the object version (ETag); a different version
 *       re-downloads over the same path (atomic replace).</li>
 *   <li>LRU by total size: past {@code maxBytes} the least recently used
 *       entries are deleted — except entries handed out within the last
 *       {@code minAge}, so a caller never loses a file it is about to open
 *       (the budget may be exceeded briefly instead).</li>
 *   <li>The file name keeps the key's last segment, so extension-sniffing
 *       parsers and LibreOffice see the same name as on local disk.</li>
 * </ul>
 */
public class LocalCopyCache {

    private static final Logger log = LoggerFactory.getLogger(LocalCopyCache.class);

    /** Writes the object's bytes to {@code target}. */
    @FunctionalInterface
    public interface Loader {
        void load(Path target) throws IOException;
    }

    private record Entry(Path path, String version, long size, long lastAccessMillis) {
        Entry touched(long now) {
            return new Entry(path, version, size, now);
        }
    }

    private final Path dir;
    private final long maxBytes;
    private final long minAgeMillis;
    private final Clock clock;
    /** Access-ordered: iteration starts at the least recently used entry. */
    private final LinkedHashMap<String, Entry> entries = new LinkedHashMap<>(16, 0.75f, true);
    private final Map<String, Object> keyLocks = new ConcurrentHashMap<>();
    private long totalBytes;

    public LocalCopyCache(Path baseDir, long maxBytes, Duration minAge, Clock clock) throws IOException {
        Files.createDirectories(baseDir);
        this.dir = FileSystems.getDefault().supportedFileAttributeViews().contains("posix")
                ? Files.createTempDirectory(baseDir, "cache-",
                        PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")))
                : Files.createTempDirectory(baseDir, "cache-");
        this.maxBytes = Math.max(0, maxBytes);
        this.minAgeMillis = minAge.toMillis();
        this.clock = clock;
    }

    /** Cache under {@code java.io.tmpdir/modocument-filestore}, budget in MiB. */
    public static LocalCopyCache inTempDir(long maxMegabytes) throws IOException {
        return new LocalCopyCache(Path.of(System.getProperty("java.io.tmpdir"), "modocument-filestore"),
                maxMegabytes * 1024L * 1024L, Duration.ofMinutes(2), Clock.systemUTC());
    }

    public Path directory() {
        return dir;
    }

    public synchronized long totalBytes() {
        return totalBytes;
    }

    /**
     * The cached copy of {@code key} at {@code version}, downloading it with
     * {@code loader} when absent or outdated.
     */
    public Path get(String key, String version, Loader loader) throws IOException {
        Path hit = lookup(key, version);
        if (hit != null) {
            return hit;
        }
        Object lock = keyLocks.computeIfAbsent(key, k -> new Object());
        synchronized (lock) {
            hit = lookup(key, version);
            if (hit != null) {
                return hit;
            }
            Path entryDir = dir.resolve(hash(key));
            Path target = entryDir.resolve(fileName(key));
            Path temp = createTemp(entryDir);
            try {
                loader.load(temp);
                try {
                    Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING,
                            StandardCopyOption.ATOMIC_MOVE);
                } catch (AtomicMoveNotSupportedException e) {
                    Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
                }
            } finally {
                Files.deleteIfExists(temp);
            }
            long size = Files.size(target);
            synchronized (this) {
                Entry previous = entries.put(key, new Entry(target, version, size, clock.millis()));
                totalBytes += size - (previous == null ? 0 : previous.size());
                evict(key);
            }
            return target;
        }
    }

    /** Forgets (and deletes) the cached copy of a key that was overwritten or deleted. */
    public void invalidate(String key) {
        Entry removed;
        synchronized (this) {
            removed = entries.remove(key);
            if (removed != null) {
                totalBytes -= removed.size();
            }
        }
        if (removed != null) {
            deleteEntry(removed.path());
        }
    }

    /** Drops every entry and the cache directory (store shutdown). */
    public void clear() {
        synchronized (this) {
            entries.clear();
            totalBytes = 0;
        }
        if (!Files.exists(dir)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(dir)) {
            paths.sorted(Comparator.reverseOrder()).forEach(LocalCopyCache::deleteQuietly);
        } catch (IOException e) {
            log.warn("Could not clear the file-store cache {}: {}", dir, e.getMessage());
        }
    }

    private synchronized Path lookup(String key, String version) {
        Entry entry = entries.get(key);
        if (entry == null || !Objects.equals(entry.version(), version) || !Files.isRegularFile(entry.path())) {
            return null;
        }
        entries.put(key, entry.touched(clock.millis()));
        return entry.path();
    }

    /** Caller holds the monitor. */
    private void evict(String keep) {
        long now = clock.millis();
        Iterator<Map.Entry<String, Entry>> lru = entries.entrySet().iterator();
        while (totalBytes > maxBytes && lru.hasNext()) {
            Map.Entry<String, Entry> candidate = lru.next();
            Entry entry = candidate.getValue();
            if (candidate.getKey().equals(keep) || now - entry.lastAccessMillis() < minAgeMillis) {
                continue;
            }
            lru.remove();
            totalBytes -= entry.size();
            deleteEntry(entry.path());
        }
    }

    /**
     * A download temp file in the key's directory. Retried once: evicting
     * this key's previous version may remove the (empty) directory between
     * the two calls.
     */
    private static Path createTemp(Path entryDir) throws IOException {
        for (int attempt = 1; ; attempt++) {
            Files.createDirectories(entryDir);
            try {
                return Files.createTempFile(entryDir, ".download-", ".tmp");
            } catch (java.nio.file.NoSuchFileException e) {
                if (attempt >= 3) {
                    throw e;
                }
            }
        }
    }

    private static String fileName(String key) {
        int slash = key.lastIndexOf('/');
        return slash < 0 ? key : key.substring(slash + 1);
    }

    private static String hash(String key) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 16);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The cached file and its (then empty) per-key directory. */
    private static void deleteEntry(Path path) {
        deleteQuietly(path);
        deleteQuietly(path.getParent());
    }

    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            log.debug("Could not delete cached file {}: {}", path, e.getMessage());
        }
    }
}
