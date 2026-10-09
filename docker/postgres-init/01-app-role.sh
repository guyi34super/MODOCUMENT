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

# Docker-secrets alternative (SEC-018): MODOCUMENT_APP_DB_PASSWORD_FILE names
# a file holding the password (surrounding whitespace trimmed, as the app's
# SecretFileEnvironmentPostProcessor does). Exactly one form may be set.
if [ -n "${MODOCUMENT_APP_DB_PASSWORD_FILE:-}" ]; then
    if [ -n "${MODOCUMENT_APP_DB_PASSWORD:-}" ]; then
        echo "01-app-role.sh: set MODOCUMENT_APP_DB_PASSWORD or" \
             "MODOCUMENT_APP_DB_PASSWORD_FILE, not both" >&2
        exit 1
    fi
    MODOCUMENT_APP_DB_PASSWORD="$(< "$MODOCUMENT_APP_DB_PASSWORD_FILE")"
    MODOCUMENT_APP_DB_PASSWORD="${MODOCUMENT_APP_DB_PASSWORD#"${MODOCUMENT_APP_DB_PASSWORD%%[![:space:]]*}"}"
    MODOCUMENT_APP_DB_PASSWORD="${MODOCUMENT_APP_DB_PASSWORD%"${MODOCUMENT_APP_DB_PASSWORD##*[![:space:]]}"}"
fi

# No insecure fallback: docker-compose requires the variable, and a first
# boot without it must fail loudly rather than create a guessable role.
APP_ROLE_PASSWORD="${MODOCUMENT_APP_DB_PASSWORD:?MODOCUMENT_APP_DB_PASSWORD (or _FILE) must be set}"

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
