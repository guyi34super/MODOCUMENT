package com.modocument.infrastructure.release;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** ws24: Keep a Changelog parsing for GET /api/system/release-notes. */
class ReleaseNotesParserTest {

    private static final String CHANGELOG = """
            # Changelog

            Intro paragraph, not an entry.
            - an intro bullet, not an entry either

            ## [Unreleased]

            ### Added
            - work in progress is never served

            ## [1.1.0] - 2026-11-01

            ### Security
            - Pinned `nginx` to 1.27
            ### Added
            - New **settings** page, see [the docs](https://example.com/docs)
              wrapped onto a second line
              - with a nested bullet
            * Star bullet
            + Plus bullet
            ### Notes
            - unknown sections are dropped
            ### fixed
            - Lower-case section headings are accepted

            ## [1.1.0-rc.1] - 2026-10-25

            ### Changed
            - Release candidate

            ## [1.0.0] - 2026-10-10
            ### Removed
            - Old thing
            ### Deprecated
            - Legacy endpoint

            ## [1.10.0] - 2027-03-01
            ### Changed
            - Out of order in the file, still sorted by version

            ## [1.0.0] - 2020-01-01
            ### Added
            - duplicate heading ignored

            ## 0.9.0
            ### Fixed
            - Heading without brackets or date

            ## Not a version
            ### Added
            - ignored

            [Unreleased]: https://example.com/compare/v1.1.0...HEAD
            [1.1.0]: https://example.com/compare/v1.0.0...v1.1.0
            """;

    @Test
    void parsesReleasedVersionsNewestFirstWithoutUnreleased() {
        List<ReleaseNotes> releases = ReleaseNotesParser.parse(CHANGELOG);

        assertThat(releases).extracting(ReleaseNotes::version)
                .containsExactly("1.10.0", "1.1.0", "1.1.0-rc.1", "1.0.0", "0.9.0");
        assertThat(releases).extracting(ReleaseNotes::date)
                .containsExactly("2027-03-01", "2026-11-01", "2026-10-25", "2026-10-10", null);
    }

    @Test
    void sectionsAreCanonicalOrderedAndPlainText() {
        ReleaseNotes release = byVersion("1.1.0");

        assertThat(release.sections().keySet()).containsExactly("Added", "Fixed", "Security");
        assertThat(release.sections().get("Added")).containsExactly(
                "New settings page, see the docs wrapped onto a second line with a nested bullet",
                "Star bullet",
                "Plus bullet");
        assertThat(release.sections().get("Security")).containsExactly("Pinned nginx to 1.27");
        assertThat(release.sections().get("Fixed"))
                .containsExactly("Lower-case section headings are accepted");

        assertThat(byVersion("1.0.0").sections()).isEqualTo(Map.of(
                "Deprecated", List.of("Legacy endpoint"),
                "Removed", List.of("Old thing")));
        assertThat(byVersion("1.0.0").sections().keySet()).containsExactly("Deprecated", "Removed");
    }

    @Test
    void theFirstOfDuplicateHeadingsWins() {
        assertThat(byVersion("1.0.0").date()).isEqualTo("2026-10-10");
        assertThat(byVersion("1.0.0").sections()).doesNotContainKey("Added");
    }

    @Test
    void emptyOrForeignInputYieldsNoReleases() {
        assertThat(ReleaseNotesParser.parse("")).isEmpty();
        assertThat(ReleaseNotesParser.parse("# Changelog\n\n## [Unreleased]\n### Added\n- x\n")).isEmpty();
        assertThat(ReleaseNotesParser.parse("<html><body>not markdown</body></html>")).isEmpty();
    }

    @Test
    void sectionsAndEntriesAreImmutable() {
        ReleaseNotes release = byVersion("1.1.0");
        assertThatThrownBy(() -> release.sections().put("Added", List.of()))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> release.sections().get("Added").add("x"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void theRepositoryChangelogParsesAndItsNewestReleaseIsDated() throws Exception {
        List<ReleaseNotes> releases = ReleaseNotesParser.parse(Files.readString(Path.of("CHANGELOG.md")));

        assertThat(releases).isNotEmpty();
        assertThat(releases.get(0).date()).matches("\\d{4}-\\d{2}-\\d{2}");
        releases.forEach(release -> {
            assertThat(release.sections()).as("release %s has entries", release.version()).isNotEmpty();
            assertThat(ReleaseNotesParser.SECTIONS).containsAll(release.sections().keySet());
        });
    }

    private static ReleaseNotes byVersion(String version) {
        return ReleaseNotesParser.parse(CHANGELOG).stream()
                .filter(release -> release.version().equals(version))
                .findFirst()
                .orElseThrow();
    }
}
