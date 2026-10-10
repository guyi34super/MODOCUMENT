package com.modocument.infrastructure.release;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.MatchResult;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses a Keep a Changelog file (ws24) into {@link ReleaseNotes}, newest
 * version first. Deliberately small and forgiving: it never throws on
 * content, it skips what it does not understand.
 *
 * <ul>
 *   <li>{@code ## [X.Y.Z] - YYYY-MM-DD} starts a release (brackets and date
 *       optional, SemVer pre-release suffixes allowed);
 *       {@code ## [Unreleased]} and non-version headings are skipped.</li>
 *   <li>{@code ### Added|Changed|Deprecated|Removed|Fixed|Security} starts
 *       a section (case-insensitive); any other {@code ###} heading's
 *       entries are dropped.</li>
 *   <li>A top-level bullet ({@code -}, {@code *}, {@code +}) is one entry;
 *       indented lines (wrapped text or nested bullets) join it.</li>
 *   <li>Entries become plain text for display: inline code ticks and
 *       emphasis markers are removed, links keep their text.</li>
 * </ul>
 *
 * <p>scripts/check-version-sync.sh enforces the stricter heading format in
 * CI; this parser only has to read what that check lets through.
 */
public final class ReleaseNotesParser {

    /** Keep a Changelog section names, in the order the API returns them. */
    public static final List<String> SECTIONS =
            List.of("Added", "Changed", "Deprecated", "Removed", "Fixed", "Security");

    private static final Pattern RELEASE_HEADING = Pattern.compile(
            "^##\\s+\\[?([^\\]\\s]+)]?(?:\\s+-\\s+(\\d{4}-\\d{2}-\\d{2}))?.*$");
    private static final Pattern SEMVER = Pattern.compile(
            "^(\\d+)\\.(\\d+)\\.(\\d+)(?:-([0-9A-Za-z.-]+))?$");
    private static final Pattern SECTION_HEADING = Pattern.compile("^###\\s+(.+?)\\s*$");
    private static final Pattern BULLET = Pattern.compile("^[-*+]\\s+(.*)$");
    private static final Pattern NESTED_BULLET = Pattern.compile("^\\s+[-*+]\\s+(.*)$");
    private static final Pattern LINK_REFERENCE = Pattern.compile("^\\[[^]]+]:\\s");
    private static final Pattern INLINE_LINK = Pattern.compile("\\[([^]]+)]\\(([^)]*)\\)");
    private static final Pattern REFERENCE_LINK = Pattern.compile("\\[([^]]+)]\\[[^]]*]");

    private ReleaseNotesParser() {
    }

    /** All released versions of the changelog, newest SemVer first. */
    public static List<ReleaseNotes> parse(String markdown) {
        List<Draft> drafts = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        Draft release = null;
        List<String> section = null;
        StringBuilder entry = null;

        for (String raw : markdown.split("\\R", -1)) {
            String line = raw.stripTrailing();

            if (line.startsWith("#")) {
                flush(section, entry);
                entry = null;
                section = null;
                if (line.startsWith("###")) {
                    if (release != null) {
                        section = release.section(line);
                    }
                } else if (line.startsWith("##")) {
                    release = startRelease(line, seen);
                    if (release != null) {
                        drafts.add(release);
                    }
                } else {
                    release = null; // "# Changelog" title or another top heading
                }
                continue;
            }
            if (line.isBlank() || LINK_REFERENCE.matcher(line).find()) {
                flush(section, entry);
                entry = null;
                continue;
            }
            Matcher bullet = BULLET.matcher(line);
            if (bullet.matches()) {
                flush(section, entry);
                entry = new StringBuilder(bullet.group(1));
                continue;
            }
            if (entry != null && Character.isWhitespace(line.charAt(0))) {
                Matcher nested = NESTED_BULLET.matcher(line);
                entry.append(' ').append(nested.matches() ? nested.group(1) : line.strip());
                continue;
            }
            // Free text under a heading (an intro paragraph): not an entry.
            flush(section, entry);
            entry = null;
        }
        flush(section, entry);

        return drafts.stream()
                .sorted(Comparator.comparing((Draft d) -> d.semver, ReleaseNotesParser::compareSemver)
                        .reversed())
                .map(Draft::toReleaseNotes)
                .toList();
    }

    private static Draft startRelease(String line, Set<String> seen) {
        Matcher heading = RELEASE_HEADING.matcher(line);
        if (!heading.matches()) {
            return null;
        }
        String version = heading.group(1);
        Matcher semver = SEMVER.matcher(version);
        if (!semver.matches() || !seen.add(version)) {
            return null; // [Unreleased], a non-version heading, or a duplicate
        }
        return new Draft(version, heading.group(2), semver.toMatchResult());
    }

    private static void flush(List<String> section, StringBuilder entry) {
        if (section == null || entry == null) {
            return;
        }
        String text = plainText(entry.toString());
        if (!text.isEmpty()) {
            section.add(text);
        }
    }

    /** Markdown inline syntax to display text. */
    static String plainText(String markdown) {
        String text = INLINE_LINK.matcher(markdown).replaceAll(m -> Matcher.quoteReplacement(m.group(1)));
        text = REFERENCE_LINK.matcher(text).replaceAll(m -> Matcher.quoteReplacement(m.group(1)));
        text = text.replace("`", "").replace("**", "").replace("__", "");
        return text.replaceAll("\\s+", " ").strip();
    }

    /** SemVer precedence: numeric core, then a release outranks its pre-releases. */
    static int compareSemver(MatchResult a, MatchResult b) {
        for (int group = 1; group <= 3; group++) {
            int cmp = new BigInteger(a.group(group))
                    .compareTo(new BigInteger(b.group(group)));
            if (cmp != 0) {
                return cmp;
            }
        }
        String preA = a.group(4);
        String preB = b.group(4);
        if (preA == null || preB == null) {
            return preA == null ? (preB == null ? 0 : 1) : -1;
        }
        String[] partsA = preA.split("\\.");
        String[] partsB = preB.split("\\.");
        for (int i = 0; i < Math.min(partsA.length, partsB.length); i++) {
            boolean numA = partsA[i].matches("\\d+");
            boolean numB = partsB[i].matches("\\d+");
            int cmp;
            if (numA && numB) {
                cmp = new BigInteger(partsA[i]).compareTo(new BigInteger(partsB[i]));
            } else if (numA != numB) {
                cmp = numA ? -1 : 1; // numeric identifiers sort before alphanumeric ones
            } else {
                cmp = partsA[i].compareTo(partsB[i]);
            }
            if (cmp != 0) {
                return cmp;
            }
        }
        return Integer.compare(partsA.length, partsB.length);
    }

    /** A release being collected; sections keyed by canonical name. */
    private static final class Draft {
        private final String version;
        private final String date;
        private final MatchResult semver;
        private final Map<String, List<String>> sections = new LinkedHashMap<>();

        Draft(String version, String date, MatchResult semver) {
            this.version = version;
            this.date = date;
            this.semver = semver;
        }

        /** The entry list for a "### Name" heading, or null for an unknown section. */
        List<String> section(String headingLine) {
            Matcher heading = SECTION_HEADING.matcher(headingLine);
            if (!heading.matches()) {
                return null;
            }
            String name = heading.group(1).toLowerCase(Locale.ROOT);
            return SECTIONS.stream()
                    .filter(canonical -> canonical.toLowerCase(Locale.ROOT).equals(name))
                    .findFirst()
                    .map(canonical -> sections.computeIfAbsent(canonical, k -> new ArrayList<>()))
                    .orElse(null);
        }

        ReleaseNotes toReleaseNotes() {
            Map<String, List<String>> ordered = new LinkedHashMap<>();
            for (String name : SECTIONS) {
                List<String> entries = sections.get(name);
                if (entries != null && !entries.isEmpty()) {
                    ordered.put(name, entries);
                }
            }
            return new ReleaseNotes(version, date, ordered);
        }
    }
}
