package com.modocument.release;

import com.modocument.release.MigrationFiles.Migration;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Flyway version ranges (ws24): {@code db/migration/RANGES.md} partitions
 * version numbers per workstream so parallel branches never collide, and
 * its tables are authoritative. This test parses both tables (the security
 * program's A-table and the phase-2 table) plus the two leading bullet
 * ranges (V1–V9 baseline, V10–V19 security foundation) and asserts:
 * <ul>
 *   <li>every V*.sql version lies in exactly one declared range, never in a
 *       reserved one;</li>
 *   <li>no two files carry the same version (Flyway normalisation:
 *       {@code V1_1} = {@code V1.1}, leading zeros ignored);</li>
 *   <li>from V20, the {@code wsNN} in the file name is the workstream that
 *       owns the range (A02 → ws02, ws24 → ws24);</li>
 *   <li>the folder holds only versioned migrations and Flyway callbacks — no
 *       undo ({@code U…}) scripts: rollback is the previous image, never a
 *       Flyway undo (docs/release/migration-policy.md).</li>
 * </ul>
 */
class FlywayVersionRangeTest {

    static final Path RANGES = MigrationFiles.FOLDER.resolve("RANGES.md");

    private static final Pattern TABLE_RANGE = Pattern.compile("V(\\d+)\\s*[–—-]\\s*V(\\d+)");
    private static final Pattern BULLET_RANGE = Pattern.compile(
            "^-\\s+\\*\\*V(\\d+)\\s*[–—-]\\s*V(\\d+)\\*\\*\\s*[–—-]\\s*(.*)$");
    private static final Pattern WORKSTREAM_CELL = Pattern.compile("^(?:A|ws)(\\d{1,2})$", Pattern.CASE_INSENSITIVE);
    private static final Pattern FILE_WORKSTREAM = Pattern.compile("^V\\d+__ws(\\d{2})_");
    private static final Pattern CALLBACK = Pattern.compile(
            "^(?:before|after)[A-Z][A-Za-z]*(?:__[A-Za-z0-9_]+)?\\.sql$");

    /** One declared range; workstream null for the baseline/foundation bullets and "—" rows. */
    record Range(int from, int to, Integer workstream, boolean reserved, String source) {

        boolean contains(int version) {
            return version >= from && version <= to;
        }
    }

    private static List<Range> ranges;

    @BeforeAll
    static void parseRanges() throws Exception {
        ranges = parse(Files.readAllLines(RANGES));
    }

    static List<Range> parse(List<String> lines) {
        List<Range> parsed = new ArrayList<>();
        for (String raw : lines) {
            String line = raw.strip();
            Matcher bullet = BULLET_RANGE.matcher(line);
            if (bullet.matches()) {
                parsed.add(new Range(Integer.parseInt(bullet.group(1)), Integer.parseInt(bullet.group(2)),
                        null, false, line));
                continue;
            }
            if (!line.startsWith("|")) {
                continue;
            }
            String[] cells = line.substring(1).split("\\|");
            if (cells.length < 2) {
                continue;
            }
            Matcher range = TABLE_RANGE.matcher(cells[1]);
            if (!range.find()) {
                continue; // header or separator row
            }
            Matcher workstream = WORKSTREAM_CELL.matcher(cells[0].strip());
            parsed.add(new Range(Integer.parseInt(range.group(1)), Integer.parseInt(range.group(2)),
                    workstream.matches() ? Integer.valueOf(workstream.group(1)) : null,
                    cells[1].toLowerCase(Locale.ROOT).contains("reserved"), line));
        }
        return parsed;
    }

    @Test
    void rangesMdDeclaresBothTablesWithoutOverlap() {
        assertThat(ranges).as("RANGES.md parsed").hasSizeGreaterThan(20);
        // The phase-2 table (docs/phase2/contracts.md): ws20 … ws26.
        for (int ws = 20; ws <= 26; ws++) {
            int workstream = ws;
            assertThat(ranges).as("phase-2 row ws%d", ws).anyMatch(range ->
                    Integer.valueOf(workstream).equals(range.workstream())
                            && range.from() == workstream * 10 && range.to() == workstream * 10 + 9);
        }
        // The A-table: A02 owns V20–V29.
        assertThat(ranges).anyMatch(range -> Integer.valueOf(2).equals(range.workstream())
                && range.from() == 20 && range.to() == 29);
        assertThat(ranges).anyMatch(range -> range.from() == 1 && range.to() == 9);

        List<String> overlaps = new ArrayList<>();
        for (int i = 0; i < ranges.size(); i++) {
            Range a = ranges.get(i);
            assertThat(a.from()).as("range bounds: %s", a.source()).isLessThanOrEqualTo(a.to());
            for (int j = i + 1; j < ranges.size(); j++) {
                Range b = ranges.get(j);
                if (a.from() <= b.to() && b.from() <= a.to()) {
                    overlaps.add(a.source() + "  <->  " + b.source());
                }
            }
        }
        assertThat(overlaps).as("overlapping ranges in RANGES.md").isEmpty();
    }

    @Test
    void everyMigrationLiesInADeclaredUnreservedRange() {
        List<String> problems = new ArrayList<>();
        for (Migration migration : MigrationFiles.versioned()) {
            List<Range> owners = ranges.stream().filter(range -> range.contains(migration.major())).toList();
            if (owners.size() != 1) {
                problems.add(migration.fileName() + ": in " + owners.size()
                        + " declared ranges (needs exactly one row of db/migration/RANGES.md)");
            } else if (owners.get(0).reserved()) {
                problems.add(migration.fileName() + ": in a RESERVED range (" + owners.get(0).source() + ")");
            }
        }
        assertThat(problems).isEmpty();
    }

    @Test
    void noTwoMigrationsShareAVersion() {
        Map<String, List<String>> byVersion = new HashMap<>();
        for (Migration migration : MigrationFiles.versioned()) {
            byVersion.computeIfAbsent(migration.version(), v -> new ArrayList<>()).add(migration.fileName());
        }
        assertThat(byVersion.values().stream().filter(files -> files.size() > 1).toList())
                .as("duplicate Flyway versions").isEmpty();
    }

    @Test
    void fileWorkstreamOwnsTheRange() {
        List<String> problems = new ArrayList<>();
        for (Migration migration : MigrationFiles.versioned()) {
            if (migration.major() < MigrationPolicyTest.NAMING_FROM) {
                continue;
            }
            Range owner = ranges.stream().filter(range -> range.contains(migration.major()))
                    .findFirst().orElse(null);
            Matcher ws = FILE_WORKSTREAM.matcher(migration.fileName());
            if (owner == null || owner.workstream() == null) {
                continue; // reported by everyMigrationLiesInADeclaredUnreservedRange
            }
            if (!ws.find()) {
                problems.add(migration.fileName() + ": name lacks __ws" + String.format("%02d", owner.workstream())
                        + "_ (MigrationPolicyTest naming)");
            } else if (Integer.parseInt(ws.group(1)) != owner.workstream()) {
                problems.add(migration.fileName() + ": named ws" + ws.group(1) + " but V" + migration.major()
                        + " belongs to workstream " + String.format("%02d", owner.workstream())
                        + " (" + owner.source() + ")");
            }
        }
        assertThat(problems).isEmpty();
    }

    @Test
    void theFolderHoldsOnlyVersionedMigrationsAndCallbacks() {
        List<String> unexpected = MigrationFiles.fileNames().stream()
                .filter(name -> !name.equals("RANGES.md"))
                .filter(name -> !MigrationFiles.VERSIONED.matcher(name).matches())
                .filter(name -> !CALLBACK.matcher(name).matches())
                .filter(name -> !MigrationFiles.SCRIPT_CONFIG.matcher(name).matches())
                .toList();

        assertThat(unexpected)
                .as("Only V{n}__*.sql migrations, their Flyway script configs (V{n}__*.sql.conf) "
                        + "and Flyway callbacks (afterMigrate*.sql) belong here; no undo (U*) or "
                        + "repeatable (R__*) scripts, no stray files Flyway would ignore")
                .isEmpty();
    }

    /**
     * ws27: a Flyway script config ({@code V270__….sql.conf}) sits next to
     * the migration it configures and may only switch the transaction off
     * (needed for CREATE INDEX CONCURRENTLY) — never re-route or re-encode a
     * migration behind the reviewers' back.
     */
    @Test
    void scriptConfigsBelongToAMigrationAndOnlyDisableTheTransaction() throws Exception {
        List<String> names = MigrationFiles.fileNames();
        List<String> problems = new ArrayList<>();
        for (String name : names) {
            Matcher config = MigrationFiles.SCRIPT_CONFIG.matcher(name);
            if (!config.matches()) {
                continue;
            }
            String migration = config.group(1);
            if (!names.contains(migration)) {
                problems.add(name + ": no migration " + migration + " next to it");
            }
            for (String line : Files.readAllLines(MigrationFiles.FOLDER.resolve(name))) {
                String setting = line.strip();
                if (setting.isEmpty() || setting.startsWith("#")) {
                    continue;
                }
                if (!setting.replace(" ", "").equals("executeInTransaction=false")) {
                    problems.add(name + ": unexpected setting '" + setting
                            + "' (only executeInTransaction=false is allowed)");
                }
            }
        }
        assertThat(problems).isEmpty();
    }

    @Test
    void parserReadsBothTableShapes() {
        List<Range> sample = parse(List.of(
                "- **V1–V9** — pre-program baseline.",
                "| Workstream | Range     |",
                "|-----------:|-----------|",
                "| A02        | V20–V29   |",
                "| A19        | V170–V179 (reserved, no migrations) |",
                "| —          | V180–V199 (reserved) |",
                "| Workstream | Range     | Owner | Content |",
                "| ws24       | V240–V249 | SE5   | DeployedVersion, Announcement |"));

        assertThat(sample).extracting(Range::from, Range::to, Range::workstream, Range::reserved)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(1, 9, null, false),
                        org.assertj.core.groups.Tuple.tuple(20, 29, 2, false),
                        org.assertj.core.groups.Tuple.tuple(170, 179, 19, true),
                        org.assertj.core.groups.Tuple.tuple(180, 199, null, true),
                        org.assertj.core.groups.Tuple.tuple(240, 249, 24, false));
    }
}
