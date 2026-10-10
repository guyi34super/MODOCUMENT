-- ws24 (phase 2, software updates — docs/release/runbook.md): one row per
-- release version the first time it started in this environment's database.
--
--  * Version — the running build's version (pom.xml, BuildProperties); the
--    PRIMARY KEY is the "first task wins" lock: every task of a rolling
--    deploy inserts its version at startup (DeployedVersionRecorder) and
--    exactly one insert succeeds. Only that winner audits VERSION_DEPLOYED in
--    every bank and publishes the RELEASE announcement; the others hit the
--    duplicate key and do nothing.
--  * GitCommit — abbreviated commit id of the build (null without git
--    metadata). Named GitCommit, not Commit: COMMIT is an SQL keyword.
--  * DeployedAt — when the version first started here; reported as
--    deployedAt by GET /api/system/version.
--  * Environment — MODOCUMENT_DEPLOY_ENVIRONMENT (staging, production,
--    local).
--
-- NON-TENANT TABLE: deliberately no BankId, so the afterMigrate RLS callback
-- leaves it alone — a deployment belongs to the whole installation, not to
-- a bank, and the rows carry no customer data (a version, a commit id, a
-- time, an environment name). It must be listed in the non-tenant table
-- inventory test (NonTenantTableInventoryPgIT, ws20).
--
-- EXPAND/CONTRACT (docs/release/migration-policy.md): a new table only. The
-- previous release never reads it; rolling the application back leaves the
-- table in place, and the older version's next roll-forward inserts nothing
-- new (its row already exists — the recorder is idempotent per version).
--
-- SCHEMA PARITY: the test profile builds its H2 schema from the JPA entities
-- (ddl-auto: create, Flyway disabled), so the DeployedVersion entity mirrors
-- this file by hand.

CREATE TABLE IF NOT EXISTS DeployedVersion (
    Version     varchar(64) PRIMARY KEY,
    GitCommit   varchar(40),
    DeployedAt  timestamptz NOT NULL,
    Environment varchar(32) NOT NULL
);
