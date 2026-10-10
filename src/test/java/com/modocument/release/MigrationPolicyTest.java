package com.modocument.release;

import com.modocument.release.MigrationFiles.Migration;
import com.modocument.release.MigrationSqlLinter.Finding;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Safe migrations (ws24, docs/release/migration-policy.md): every Flyway
 * migration from V{@value #POLICY_FROM} on must leave the schema usable by
 * the PREVIOUS release, because rollback means redeploying the previous
 * image on the migrated database (never a Flyway undo).
 *
 * <ul>
 *   <li>Release N only expands: new tables, nullable or defaulted columns,
 *       indexes, NOT VALID constraints, widened CHECKs (drop + re-add by
 *       name in the same file).</li>
 *   <li>Contract steps — DROP TABLE/COLUMN/CONSTRAINT/VIEW, RENAME,
 *       ALTER COLUMN … TYPE, SET NOT NULL, VALIDATE CONSTRAINT — only in a
 *       file headed {@code -- contract: expanded-in vX.Y.Z}, where vX.Y.Z is
 *       an already released version (a CHANGELOG.md release heading).</li>
 *   <li>{@code ADD COLUMN … NOT NULL} always needs a DEFAULT.</li>
 *   <li>From V{@value #NAMING_FROM}: {@code V{n}__ws{NN}_{desc}.sql},
 *       lower-case description (FlywayVersionRangeTest checks that NN owns
 *       the range).</li>
 * </ul>
 * The historical files below V200 predate the policy and are not linted
 * (several used SET NOT NULL when the tables were still empty).
 */
class MigrationPolicyTest {

    static final int POLICY_FROM = 200;
    static final int NAMING_FROM = 20;

    static final Pattern NAME = Pattern.compile("^V(\\d+)__ws(\\d{2})_[a-z0-9]+(?:_[a-z0-9]+)*\\.sql$");

    private static final Pattern CHANGELOG_RELEASE = Pattern.compile(
            "^## \\[(\\d+\\.\\d+\\.\\d+)] - \\d{4}-\\d{2}-\\d{2}\\s*$", Pattern.MULTILINE);

    // ------------------------------------------------------------ repository

    @Test
    void migrationsFromV200FollowExpandContract() throws Exception {
        List<String> released = releasedVersions();
        List<String> problems = new ArrayList<>();

        for (Migration migration : MigrationFiles.versioned()) {
            if (migration.major() < POLICY_FROM) {
                continue;
            }
            String sql = migration.sql();
            Optional<String> expandedIn = MigrationSqlLinter.contractHeader(sql);
            if (MigrationSqlLinter.hasMalformedContractHeader(sql)) {
                problems.add(migration.fileName() + ": malformed contract header — write exactly "
                        + "'-- contract: expanded-in vX.Y.Z' before the first statement");
            }
            if (expandedIn.isPresent() && !released.contains(expandedIn.get())) {
                problems.add(migration.fileName() + ": contract header names v" + expandedIn.get()
                        + ", which is not a released version in CHANGELOG.md (released: " + released + ")");
            }
            for (Finding finding : MigrationSqlLinter.lint(sql)) {
                if (!finding.contract() || expandedIn.isEmpty()) {
                    problems.add(migration.fileName() + ": " + finding);
                }
            }
        }

        assertThat(problems)
                .as("Expand/contract violations (docs/release/migration-policy.md). A contract step "
                        + "ships one release after its expand step, in a file headed "
                        + "'-- contract: expanded-in vX.Y.Z'.")
                .isEmpty();
    }

    @Test
    void migrationsFromV20AreNamedWsNnDescription() {
        List<String> misnamed = MigrationFiles.versioned().stream()
                .filter(migration -> migration.major() >= NAMING_FROM)
                .map(Migration::fileName)
                .filter(name -> !NAME.matcher(name).matches())
                .toList();

        assertThat(misnamed)
                .as("Migrations from V%d must be named V{n}__ws{NN}_{lower_case_description}.sql "
                        + "(db/migration/RANGES.md)", NAMING_FROM)
                .isEmpty();
    }

    // ------------------------------------------------------------ the rules

    @ParameterizedTest
    @ValueSource(strings = {
            "DROP TABLE Foo;",
            "DROP TABLE IF EXISTS Foo CASCADE;",
            "ALTER TABLE Foo DROP COLUMN Bar;",
            "ALTER TABLE Foo DROP COLUMN IF EXISTS Bar;",
            "ALTER TABLE Foo DROP Bar;",
            "ALTER TABLE IF EXISTS ONLY Foo DROP IF EXISTS Bar;",
            "ALTER TABLE Foo ADD COLUMN Baz int, DROP Bar;",
            "ALTER TABLE Foo DROP CONSTRAINT ck_foo;",
            "ALTER TABLE Foo DROP CONSTRAINT IF EXISTS \"ck_foo\";",
            "ALTER TABLE Foo RENAME TO Bar;",
            "ALTER TABLE Foo RENAME COLUMN A TO B;",
            "ALTER TABLE Foo RENAME CONSTRAINT a TO b;",
            "ALTER INDEX ix_foo RENAME TO ix_bar;",
            "ALTER TABLE Foo ALTER COLUMN Bar TYPE bigint;",
            "ALTER TABLE Foo ALTER COLUMN Bar SET DATA TYPE varchar(50);",
            "ALTER TABLE Foo ALTER Bar TYPE text USING Bar::text;",
            "ALTER TABLE Foo ALTER COLUMN Type TYPE text;",
            "ALTER TABLE Foo ALTER COLUMN Bar SET NOT NULL;",
            "ALTER TABLE Foo VALIDATE CONSTRAINT fk_foo_bar;",
            "DROP VIEW v_foo;",
            "DROP MATERIALIZED VIEW IF EXISTS v_foo;",
            "DO $$ BEGIN ALTER TABLE Foo DROP COLUMN Bar; END $$;",
            "DO $body$ BEGIN EXECUTE 'x'; ALTER TABLE Foo ALTER COLUMN Bar SET NOT NULL; END $body$;",
            "alter table foo\n    alter column bar\n    set not null;",
    })
    void contractStepsAreFlaggedAndExemptedOnlyByTheHeader(String sql) {
        List<Finding> findings = MigrationSqlLinter.lint(sql);

        assertThat(findings).as("findings for: %s", sql).isNotEmpty().allMatch(Finding::contract);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "CREATE TABLE Foo (Id bigserial PRIMARY KEY, Name varchar(200) NOT NULL, Kind varchar(16) "
                    + "NOT NULL CHECK (Kind IN ('A', 'B')));",
            "ALTER TABLE Foo ADD COLUMN Bar int;",
            "ALTER TABLE Foo ADD COLUMN Bar int NOT NULL DEFAULT 0;",
            "ALTER TABLE Foo ADD COLUMN IF NOT EXISTS Bar varchar(16) NOT NULL DEFAULT 'CUSTOMER';",
            "ALTER TABLE Foo ADD COLUMN Id bigint GENERATED ALWAYS AS IDENTITY NOT NULL;",
            "ALTER TABLE Foo ADD COLUMN Seq bigserial NOT NULL;",
            "ALTER TABLE Foo ADD Bar numeric(10,2) NOT NULL DEFAULT 0, ADD COLUMN Baz text;",
            "ALTER TABLE Foo ADD CONSTRAINT fk_foo_bar FOREIGN KEY (BarId) REFERENCES Bar (Id) NOT VALID;",
            "ALTER TABLE Foo ADD CONSTRAINT ck_not_null CHECK (Bar IS NOT NULL) NOT VALID;",
            "ALTER TABLE Foo ALTER COLUMN Bar DROP NOT NULL;",
            "ALTER TABLE Foo ALTER COLUMN Bar DROP DEFAULT, ALTER COLUMN Baz SET DEFAULT 1;",
            "ALTER TABLE Foo ALTER COLUMN Type SET DEFAULT 'x';",
            "ALTER TABLE Type ADD COLUMN Bar int;",
            "CREATE INDEX CONCURRENTLY IF NOT EXISTS ix_foo_bar ON Foo (Bar);",
            "DROP INDEX IF EXISTS ix_foo_old;",
            "DROP TRIGGER IF EXISTS trg_foo ON Foo; CREATE TRIGGER trg_foo BEFORE INSERT ON Foo "
                    + "FOR EACH ROW EXECUTE FUNCTION foo_guard();",
            "ALTER TABLE AppUsers DROP CONSTRAINT ck_appusers_role;\n"
                    + "ALTER TABLE AppUsers ADD CONSTRAINT ck_appusers_role CHECK (Role IN ('ADMIN', 'X'));",
            "ALTER TABLE AppUsers DROP CONSTRAINT IF EXISTS ck_appusers_role, "
                    + "ADD CONSTRAINT ck_appusers_role CHECK (Role IN ('ADMIN', 'X'));",
            "DROP VIEW IF EXISTS v_foo; CREATE VIEW v_foo AS SELECT 1;",
            "-- DROP TABLE Foo; RENAME; SET NOT NULL in a comment\nSELECT 1;",
            "/* ALTER TABLE Foo DROP COLUMN Bar; /* nested */ still a comment */ SELECT 1;",
            "COMMENT ON TABLE Foo IS 'never DROP TABLE, RENAME or SET NOT NULL here';",
            "INSERT INTO Foo (Note) VALUES (E'it\\'s fine; DROP TABLE Foo');",
            "CREATE FUNCTION foo_guard() RETURNS trigger LANGUAGE plpgsql SECURITY DEFINER AS $$\n"
                    + "BEGIN\n  IF NEW.Role = 'PLATFORM_ADMIN' THEN\n"
                    + "    RAISE EXCEPTION 'role % not allowed; DROP TABLE x', NEW.Role;\n"
                    + "  END IF;\n  RETURN NEW;\nEND\n$$;",
            "SELECT $1, $tag$ it's a literal $tag$;",
    })
    void expandStepsPass(String sql) {
        assertThat(MigrationSqlLinter.lint(sql)).as("findings for: %s", sql).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "ALTER TABLE Foo ADD COLUMN Bar int NOT NULL;",
            "ALTER TABLE Foo ADD Bar varchar(10) NOT NULL;",
            "ALTER TABLE Foo ADD COLUMN Baz text, ADD COLUMN Bar int NOT NULL;",
            "ALTER TABLE Foo ADD COLUMN IF NOT EXISTS Bar int NOT NULL REFERENCES Bar (Id);",
    })
    void addColumnNotNullWithoutDefaultIsNeverAllowed(String sql) {
        assertThat(MigrationSqlLinter.lint(sql)).as("findings for: %s", sql)
                .anyMatch(finding -> !finding.contract()
                        && finding.rule().startsWith("ADD COLUMN ... NOT NULL"));
    }

    @Test
    void theContractHeaderMustLeadTheFileAndNameAVersion() {
        assertThat(MigrationSqlLinter.contractHeader(
                "-- V203__ws20_drop_legacy.sql\n-- contract: expanded-in v1.1.0\n\nALTER TABLE Foo DROP COLUMN Bar;"))
                .contains("1.1.0");
        assertThat(MigrationSqlLinter.contractHeader(
                "ALTER TABLE Foo DROP COLUMN Bar;\n-- contract: expanded-in v1.1.0\n"))
                .as("after the first statement it is not a header").isEmpty();

        String malformed = "-- contract: expanded in 1.1\nALTER TABLE Foo DROP COLUMN Bar;";
        assertThat(MigrationSqlLinter.contractHeader(malformed)).isEmpty();
        assertThat(MigrationSqlLinter.hasMalformedContractHeader(malformed)).isTrue();
        assertThat(MigrationSqlLinter.hasMalformedContractHeader("-- plain header\nSELECT 1;")).isFalse();
    }

    @Test
    void theLinterCatchesTheRealPrePolicyMigrations() {
        // V10/V30 ran SET NOT NULL while the tables were new — fine then,
        // a rollback hazard under the policy. Proves the lint reads real files.
        Migration v30 = MigrationFiles.versioned().stream()
                .filter(migration -> migration.major() == 30)
                .findFirst()
                .orElseThrow();
        assertThat(MigrationSqlLinter.lint(v30.sql()))
                .anyMatch(finding -> finding.rule().equals("SET NOT NULL"));
    }

    @Test
    void namingPatternAcceptsTheConventionOnly() {
        assertThat(NAME.matcher("V200__ws20_settings.sql").matches()).isTrue();
        assertThat(NAME.matcher("V241__ws24_announcement_table.sql").matches()).isTrue();
        assertThat(NAME.matcher("V200__settings.sql").matches()).isFalse();
        assertThat(NAME.matcher("V200__ws20_Settings.sql").matches()).isFalse();
        assertThat(NAME.matcher("V200__ws2_settings.sql").matches()).isFalse();
        assertThat(NAME.matcher("V200__ws20-settings.sql").matches()).isFalse();
    }

    /** Versions with a dated release heading in CHANGELOG.md. */
    static List<String> releasedVersions() throws Exception {
        String changelog = Files.readString(Path.of("CHANGELOG.md"));
        List<String> versions = new ArrayList<>();
        Matcher heading = CHANGELOG_RELEASE.matcher(changelog);
        while (heading.find()) {
            versions.add(heading.group(1));
        }
        return versions;
    }
}
