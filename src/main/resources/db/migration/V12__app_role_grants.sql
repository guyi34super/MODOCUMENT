-- Two-role database model backing row-level security (V13).
--
--  * MIGRATION OWNER — the user Flyway connects as (spring.flyway.user, env
--    MODOCUMENT_FLYWAY_USERNAME; defaults to the datasource user so existing
--    single-user setups keep working). It owns every table, so it BYPASSES
--    the non-FORCE RLS policies of V13 — exactly what migrations, backfills
--    and operational psql sessions need.
--  * modocument_app — the role the APPLICATION connects as at runtime
--    (spring.datasource.username in production/compose). Plain DML rights
--    only, owns nothing and is NOBYPASSRLS, so every query it runs is
--    filtered by the tenant policies. It must never be the Flyway user.
--
-- The role is created only when absent, with a PLACEHOLDER password:
-- provisioning must set the real one out of band (ALTER ROLE modocument_app
-- PASSWORD '...'). The dev docker-compose stack does that in its postgres
-- init script (docker/postgres-init/01-app-role.sh, driven by
-- MODOCUMENT_APP_DB_PASSWORD). CREATE ROLE is cluster-wide, hence the guard;
-- the grants below are per-database and idempotent.
--
-- NOTE for databases migrated before this file existed (V15/V20 already
-- applied): V12/V13 are below the latest applied version, so they need
-- Flyway out-of-order enabled once — or simply recreate the database
-- (pre-launch there is no data worth preserving).
--
-- This file only ever runs on PostgreSQL (the H2 local/test profiles disable
-- Flyway and build their schema from the JPA entities).

DO $role$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_catalog.pg_roles WHERE rolname = 'modocument_app') THEN
        CREATE ROLE modocument_app LOGIN NOBYPASSRLS PASSWORD 'modocument_app';
        COMMENT ON ROLE modocument_app IS
            'MoDocument runtime role, subject to RLS. Created by V12 with a '
            'placeholder password - provisioning must set the real one.';
    END IF;
END
$role$;

DO $grants$
BEGIN
    EXECUTE format('GRANT CONNECT ON DATABASE %I TO modocument_app', current_database());
    EXECUTE format('GRANT USAGE ON SCHEMA %I TO modocument_app', current_schema());
    EXECUTE format(
        'GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA %I TO modocument_app',
        current_schema());
    EXECUTE format(
        'GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA %I TO modocument_app',
        current_schema());
    -- Tables/sequences created by LATER migrations (same owner role) are
    -- granted automatically.
    EXECUTE format(
        'ALTER DEFAULT PRIVILEGES IN SCHEMA %I '
        'GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO modocument_app',
        current_schema());
    EXECUTE format(
        'ALTER DEFAULT PRIVILEGES IN SCHEMA %I '
        'GRANT USAGE, SELECT ON SEQUENCES TO modocument_app',
        current_schema());
END
$grants$;
