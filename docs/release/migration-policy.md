# Database migration policy: expand / contract

**Owner:** release engineering (ws24). **Enforced by:** `MigrationPolicyTest`
and `FlywayVersionRangeTest` (`src/test/java/com/modocument/release/`). Both
run in every `mvn -B verify`.

## Why

A release can fail after its migrations have already run. Flyway migrates
the shared PostgreSQL database when the first new task starts, and the ECS
deployment circuit breaker can then roll the service back. The rollback
starts the **previous image** on the **already migrated** schema. So
release N's migrations must leave a schema that release N−1 still works on.

**Rollback means the previous image. It never means a Flyway undo.** There
are no `U*.sql` undo scripts (the test fails on one), and nobody runs
`flyway undo` or edits `flyway_schema_history` by hand. To fix a bad
migration, ship a new forward migration in the next release.

The same rule makes rolling deploys safe. During the switch, old and new
tasks serve requests from the same database for a few minutes.

## The rules (migrations from V200 on)

### Release N may only expand

| Allowed in any release | Notes |
|---|---|
| `CREATE TABLE`, `CREATE INDEX` (use `CONCURRENTLY` on large tables) | Old code ignores new tables. |
| `ADD COLUMN` that is nullable | Old INSERTs leave it NULL. |
| `ADD COLUMN … NOT NULL DEFAULT …` (or `GENERATED` / `serial`) | Old INSERTs get the default. **`NOT NULL` without a default is never allowed**, header or not. |
| `ADD CONSTRAINT … NOT VALID` | Checks new rows only, without a full-table lock. Validate in N+1. |
| Widening a CHECK: `DROP CONSTRAINT ck_x` and `ADD CONSTRAINT ck_x …` **with the same name in the same file** | Example: adding a role or status value. Prefer a single `ALTER TABLE` statement so it is atomic. |
| `CREATE OR REPLACE FUNCTION/VIEW`, triggers, grants, policies | Keep the old signature and the old columns working. |
| Data backfills (`UPDATE … WHERE … IS NULL`) | Batch them on large tables. |

### Contract steps ship one release later (N+1)

These steps break release N−1's image, so they need a **header**:

- `DROP TABLE`, `DROP COLUMN` (also the short form `ALTER TABLE t DROP c`)
- `DROP CONSTRAINT` when the same file does not re-add it by name
- `DROP VIEW` / `DROP MATERIALIZED VIEW` when the same file does not
  re-create it by name
- `RENAME` (tables, columns, constraints, indexes)
- `ALTER COLUMN … TYPE` / `SET DATA TYPE`
- `ALTER COLUMN … SET NOT NULL`
- `VALIDATE CONSTRAINT`

The header must come before the first statement:

```sql
-- contract: expanded-in v1.1.0
-- V243__ws24_drop_legacy_announcement_text.sql
ALTER TABLE Announcement DROP COLUMN LegacyText;
```

`v1.1.0` must be a **released** version, meaning a dated `## [1.1.0]`
heading in `CHANGELOG.md`. It names the release that shipped the expand
half: the new column, and the code that stopped reading the old one. Since
the expand half is already in production, rolling back to the release
before this one is still safe. A malformed header (anything else starting
`-- contract`) fails the test, so no file is exempted by accident.

### Recipes

**Rename a column** (`Name` → `DisplayName`):

1. N: `ADD COLUMN DisplayName`, backfill it, and have the code write both
   columns and read the new one.
2. N+1 (`-- contract: expanded-in vN`): `DROP COLUMN Name`.

**Make a column required:**

1. N: backfill the NULLs, then `ADD CONSTRAINT ck_x_not_null CHECK (X IS NOT
   NULL) NOT VALID`. The code always writes the column.
2. N+1 (contract): `VALIDATE CONSTRAINT ck_x_not_null`. Optionally
   `SET NOT NULL`, which PostgreSQL then does without a scan.

**Change a column type:** add a new column of the new type, dual-write and
backfill in N, then switch reads and drop the old column in N+1.

**Widen a CHECK** (as ws21 does for `AppUsers.Role`):

```sql
ALTER TABLE AppUsers
    DROP CONSTRAINT ck_appusers_role,
    ADD CONSTRAINT ck_appusers_role
        CHECK (Role IN ('ADMIN', 'APPROVER', 'READ_WRITE', 'READ_ONLY', 'PLATFORM_ADMIN'));
```

## Version ranges and naming

- Every workstream owns a block of ten versions in
  [`src/main/resources/db/migration/RANGES.md`](../../src/main/resources/db/migration/RANGES.md).
  The tables there are authoritative. `FlywayVersionRangeTest` fails when a
  file:
  - lies outside a declared range or in a reserved one,
  - reuses a version (`V1_1` and `V1.1` count as the same),
  - carries a `wsNN` that does not own its range.
- From V20 on, files are named `V{n}__ws{NN}_{lower_case_description}.sql`.
- The folder holds only versioned migrations, Flyway callbacks
  (`afterMigrate*.sql`) and `RANGES.md`.
- Never edit or rename a migration that has been applied anywhere,
  including staging. Flyway's checksum validation stops the app on start.

## What the lint does not see

`MigrationSqlLinter` works on the text. It removes comments and string
literals, and it still checks the bodies of dollar-quoted `DO` and function
blocks. It cannot see:

- DDL built at runtime (`EXECUTE format('ALTER TABLE …')`),
- `DROP FUNCTION`, `DROP TRIGGER`, `DROP INDEX`, `DROP POLICY` (these are
  allowed),
- whether a re-added constraint is really wider than the one it replaces.

Code review covers these. A reviewer of any migration from V200 on asks:
*"Does release N−1 still work on this schema?"*

## Historical migrations

V1–V169 predate this policy. Some ran `SET NOT NULL` on tables that were new
at the time. They are not linted and must not be changed.
