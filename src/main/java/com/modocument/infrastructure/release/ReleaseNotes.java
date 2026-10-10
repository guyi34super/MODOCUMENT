package com.modocument.infrastructure.release;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One released version of CHANGELOG.md as served by
 * {@code GET /api/system/release-notes} (ws24, docs/phase2/contracts.md §3).
 *
 * @param version  SemVer of the release, e.g. {@code 1.1.0}
 * @param date     release date {@code YYYY-MM-DD}; null when the heading has none
 * @param sections Keep a Changelog section name (Added, Changed, Deprecated,
 *                 Removed, Fixed, Security — in that order, only those
 *                 present) to its entries as plain text
 */
public record ReleaseNotes(String version, String date, Map<String, List<String>> sections) {

    public ReleaseNotes {
        Map<String, List<String>> copy = new LinkedHashMap<>();
        sections.forEach((name, entries) -> copy.put(name, List.copyOf(entries)));
        sections = Collections.unmodifiableMap(copy);
    }
}
