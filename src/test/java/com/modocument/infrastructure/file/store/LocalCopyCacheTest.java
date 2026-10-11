package com.modocument.infrastructure.file.store;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class LocalCopyCacheTest {

    @TempDir
    Path base;

    private final MutableClock clock = new MutableClock();
    private final AtomicInteger downloads = new AtomicInteger();
    private LocalCopyCache cache;

    @BeforeEach
    void setUp() throws IOException {
        // 10-byte budget, 1-minute protection of freshly handed-out copies.
        cache = new LocalCopyCache(base, 10, Duration.ofMinutes(1), clock);
    }

    @AfterEach
    void tearDown() {
        cache.clear();
    }

    private LocalCopyCache.Loader bytes(String content) {
        return target -> {
            downloads.incrementAndGet();
            Files.writeString(target, content);
        };
    }

    @Test
    void sameVersionIsServedFromTheCacheANewVersionIsDownloadedAgain() throws IOException {
        Path first = cache.get("uploads/a.csv", "v1", bytes("one"));
        Path again = cache.get("uploads/a.csv", "v1", bytes("ignored"));
        assertThat(again).isEqualTo(first);
        assertThat(Files.readString(again)).isEqualTo("one");
        assertThat(downloads).hasValue(1);

        Path updated = cache.get("uploads/a.csv", "v2", bytes("two"));
        assertThat(Files.readString(updated)).isEqualTo("two");
        assertThat(updated.getFileName().toString()).isEqualTo("a.csv");
        assertThat(downloads).hasValue(2);
        assertThat(cache.totalBytes()).isEqualTo(3);
    }

    @Test
    void leastRecentlyUsedEntriesAreEvictedPastTheBudgetOnceOldEnough() throws IOException {
        Path a = cache.get("uploads/a.bin", "1", bytes("aaaa"));
        clock.advance(Duration.ofSeconds(10));
        Path b = cache.get("uploads/b.bin", "1", bytes("bbbb"));
        clock.advance(Duration.ofMinutes(2));
        cache.get("uploads/a.bin", "1", bytes("unused"));   // a is now the most recently used

        Path c = cache.get("uploads/c.bin", "1", bytes("cccc"));   // 12 bytes > 10: evict LRU (b)

        assertThat(Files.exists(b)).isFalse();
        assertThat(Files.exists(a)).isTrue();
        assertThat(Files.exists(c)).isTrue();
        assertThat(cache.totalBytes()).isEqualTo(8);
    }

    @Test
    void recentlyHandedOutCopiesSurviveEvenOverBudget() throws IOException {
        Path a = cache.get("uploads/a.bin", "1", bytes("aaaaaaaa"));
        Path b = cache.get("uploads/b.bin", "1", bytes("bbbbbbbb"));

        // Both younger than the minimum age: the budget is exceeded briefly
        // instead of deleting a file a caller may be about to open.
        assertThat(Files.exists(a)).isTrue();
        assertThat(Files.exists(b)).isTrue();
        assertThat(cache.totalBytes()).isEqualTo(16);

        clock.advance(Duration.ofMinutes(5));
        cache.get("uploads/c.bin", "1", bytes("c"));
        assertThat(cache.totalBytes()).isLessThanOrEqualTo(10);
    }

    @Test
    void invalidateAndClearDeleteTheFiles() throws IOException {
        Path a = cache.get("uploads/a.bin", "1", bytes("aa"));
        cache.invalidate("uploads/a.bin");
        assertThat(Files.exists(a)).isFalse();
        assertThat(cache.totalBytes()).isZero();

        Path b = cache.get("uploads/b.bin", "1", bytes("bb"));
        cache.clear();
        assertThat(Files.exists(b)).isFalse();
        assertThat(Files.exists(cache.directory())).isFalse();
    }

    @Test
    void aFailedDownloadLeavesNothingCached() throws IOException {
        try {
            cache.get("uploads/a.bin", "1", target -> {
                Files.writeString(target, "partial");
                throw new IOException("connection reset");
            });
        } catch (IOException expected) {
            // the caller sees the failure
        }
        assertThat(cache.totalBytes()).isZero();
        Path ok = cache.get("uploads/a.bin", "1", bytes("full"));
        assertThat(Files.readString(ok)).isEqualTo("full");
    }

    private static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-10-11T00:00:00Z");

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
