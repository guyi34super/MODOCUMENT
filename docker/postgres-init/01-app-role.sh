#!/bin/bash
# Creates the RLS-subject runtime role for the MoDocument app service.
# Runs ONLY on first database initialization (fresh volume) — the official
# postgres image executes /docker-entrypoint-initdb.d once. Migration V12
# also creates the role (with a placeholder password) so clusters that
# skipped this script still work; this script sets the REAL dev password
# from MODOCUMENT_APP_DB_PASSWORD so the app service can connect.
#
# The password travels through a psql variable (-v), never string-spliced
# into the SQL, so special characters are safe.
set -euo pipefail

APP_ROLE_PASSWORD="${MODOCUMENT_APP_DB_PASSWORD:-modocument_app}"

psql -v ON_ERROR_STOP=1 -v app_password="$APP_ROLE_PASSWORD" \
     --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" <<'EOSQL'
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_catalog.pg_roles WHERE rolname = 'modocument_app') THEN
        CREATE ROLE modocument_app LOGIN NOBYPASSRLS;
    END IF;
END
$$;
ALTER ROLE modocument_app WITH LOGIN NOBYPASSRLS PASSWORD :'app_password';
GRANT CONNECT ON DATABASE :"DBNAME" TO modocument_app;
EOSQL
