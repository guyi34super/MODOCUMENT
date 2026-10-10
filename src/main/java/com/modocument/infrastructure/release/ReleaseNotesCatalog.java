package com.modocument.infrastructure.release;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * The release notes bundled with this build (ws24): CHANGELOG.md, packaged
 * by pom.xml (copy-resources) as {@code classpath:release/CHANGELOG.md} and
 * parsed once, on first use — the file cannot change while the process
 * runs. A build without the file (an IDE run that skipped Maven's
 * process-resources) serves an empty list instead of failing.
 */
@Component
public class ReleaseNotesCatalog {

    /** Upper bound of {@code GET /api/system/release-notes?limit=}. */
    public static final int MAX_LIMIT = 20;

    private static final Logger log = LoggerFactory.getLogger(ReleaseNotesCatalog.class);

    private final Resource changelog;
    private volatile List<ReleaseNotes> releases;

    public ReleaseNotesCatalog(ResourceLoader resourceLoader,
                               @Value("${modocument.release.changelog-location:classpath:release/CHANGELOG.md}")
                               String location) {
        this.changelog = resourceLoader.getResource(location);
    }

    /** The newest {@code limit} releases (1–{@value #MAX_LIMIT}), newest first. */
    public List<ReleaseNotes> latest(int limit) {
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new IllegalArgumentException("limit must be between 1 and " + MAX_LIMIT + ".");
        }
        List<ReleaseNotes> all = all();
        return all.subList(0, Math.min(limit, all.size()));
    }

    /** Every release in the bundled changelog, newest first. */
    public List<ReleaseNotes> all() {
        List<ReleaseNotes> loaded = releases;
        if (loaded == null) {
            loaded = load();
            releases = loaded;
        }
        return loaded;
    }

    private List<ReleaseNotes> load() {
        if (!changelog.exists()) {
            log.warn("Release notes unavailable: {} is not on the classpath (built without Maven "
                    + "process-resources?)", changelog.getDescription());
            return List.of();
        }
        try (InputStream in = changelog.getInputStream()) {
            return ReleaseNotesParser.parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            log.warn("Release notes unavailable: {} could not be read ({})",
                    changelog.getDescription(), e.getClass().getSimpleName());
            return List.of();
        }
    }
}
