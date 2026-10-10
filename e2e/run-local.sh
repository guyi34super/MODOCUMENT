#!/usr/bin/env bash
# MoDocument E2E — local runner (no Docker).
#
# Builds the backend jar and the SPA, creates a FRESH database on a local
# PostgreSQL, starts the fake LLM, the backend (production profile, secrets
# generated for this run only) and the built SPA behind `vite preview` (whose
# proxy mirrors the nginx API locations), waits for health, runs Playwright,
# and tears everything down again — on success, failure or Ctrl-C.
#
#   e2e/run-local.sh                     # full suite
#   e2e/run-local.sh --grep @smoke       # extra args go to `playwright test`
#   SKIP_BUILD=1 e2e/run-local.sh        # reuse target/*.jar and frontend/dist
#   E2E_KEEP=1 e2e/run-local.sh          # keep the database + run dir for debugging
#   E2E_DB_APP_ROLE=owner e2e/run-local.sh  # backend runs as the schema owner (RLS
#                                        # bypassed) — the old BUG-01 workaround
#   E2E_BANK_SIM=1 e2e/run-local.sh      # also start the Wave-3 bank simulator
#                                        # (bank DB + REST stub; no SFTP without Docker)
#   E2E_WAVE3=1 E2E_BANK_SIM=1 e2e/run-local.sh  # ... and switch the Wave-3 specs on
#
# PostgreSQL: E2E_PG_HOST/E2E_PG_PORT/E2E_PG_SUPERUSER (default
# 127.0.0.1:55432, postgres — the sandbox scratch cluster, started here if it
# is down). The superuser is used only to create/drop the run's database and
# its owner role. The app connects as the RLS-subject role modocument_app
# (created if missing); set E2E_PG_SET_APP_PASSWORD=1 on a dedicated cluster
# that needs password auth, so the role gets this run's generated password.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

log() { printf '\033[1;34m[e2e]\033[0m %s\n' "$*"; }
die() { printf '\033[1;31m[e2e] %s\033[0m\n' "$*" >&2; exit 1; }

for tool in java node npm psql openssl curl; do
  command -v "$tool" >/dev/null 2>&1 || die "missing required tool: $tool"
done

RUN_ID="${E2E_RUN_ID:-$(openssl rand -hex 4)}"
RUN_DIR="${E2E_RUN_DIR:-$SCRIPT_DIR/.run/$RUN_ID}"
mkdir -p "$RUN_DIR/storage" "$RUN_DIR/state"
chmod 700 "$RUN_DIR"

PG_HOST="${E2E_PG_HOST:-127.0.0.1}"
PG_PORT="${E2E_PG_PORT:-55432}"
PG_SUPERUSER="${E2E_PG_SUPERUSER:-postgres}"
DB_NAME="modoc_e2e_${RUN_ID}"
DB_OWNER="modoc_e2e_owner_${RUN_ID}"
# rls (default) = production two-role model: the backend connects as
# modocument_app and every tenant table is filtered by RLS. owner = the
# backend connects as the schema owner (RLS bypassed); only for working
# around BUG-01 (MFA verify fails under RLS) — see e2e/README.md.
E2E_DB_APP_ROLE="${E2E_DB_APP_ROLE:-rls}"
case "$E2E_DB_APP_ROLE" in rls|owner) ;; *) die "E2E_DB_APP_ROLE must be rls or owner";; esac

free_port() {
  node -e "const s=require('net').createServer();s.listen(0,'127.0.0.1',()=>{console.log(s.address().port);s.close()})"
}

PIDS=()
cleanup() {
  local status=$?
  set +e
  if [ "${#PIDS[@]}" -gt 0 ]; then
    log "stopping stack (${PIDS[*]})"
    for pid in "${PIDS[@]}"; do
      # Each service runs in its own process group (setsid): stop the group,
      # so npx/vite children and the JVM go too.
      kill -TERM -- "-$pid" 2>/dev/null || kill -TERM "$pid" 2>/dev/null
    done
    for _ in $(seq 1 20); do
      alive=0
      for pid in "${PIDS[@]}"; do kill -0 "$pid" 2>/dev/null && alive=1; done
      [ "$alive" = 0 ] && break
      sleep 0.5
    done
    for pid in "${PIDS[@]}"; do kill -KILL -- "-$pid" 2>/dev/null; done
  fi
  if [ "${E2E_KEEP:-0}" != "1" ] && [ "${DB_CREATED:-0}" = "1" ]; then
    log "dropping database $DB_NAME and role $DB_OWNER"
    psql_super -c "DROP DATABASE IF EXISTS \"$DB_NAME\" WITH (FORCE)" >/dev/null
    psql_super -c "DROP ROLE IF EXISTS \"$DB_OWNER\"" >/dev/null
  fi
  if [ "${E2E_KEEP:-0}" != "1" ] && [ "${BANK_DB_CREATED:-0}" = "1" ]; then
    log "dropping bank simulator database $BANK_DB"
    psql_super -c "DROP DATABASE IF EXISTS \"$BANK_DB\" WITH (FORCE)" >/dev/null
    for role in "$BANK_ADMIN" "$BANK_READER" "$BANK_WRITER"; do
      psql_super -c "DROP ROLE IF EXISTS \"$role\"" >/dev/null
    done
  fi
  if [ "${E2E_KEEP:-0}" != "1" ]; then
    # Throwaway credentials of a throwaway stack — still, don't leave them around.
    rm -rf "$RUN_DIR/state" "$RUN_DIR/storage" "$RUN_DIR/env"
  fi
  log "logs: $RUN_DIR (backend.log, web.log, fake-llm.log); report: e2e/e2e-report/index.html"
  exit "$status"
}
trap cleanup EXIT INT TERM

psql_super() {
  psql -X -q -v ON_ERROR_STOP=1 -h "$PG_HOST" -p "$PG_PORT" -U "$PG_SUPERUSER" -d postgres "$@"
}

# --- 1. PostgreSQL ------------------------------------------------------------
if ! pg_isready -q -h "$PG_HOST" -p "$PG_PORT" 2>/dev/null; then
  if [ -d /tmp/modoc-pg16/data ] && [ "$PG_PORT" = "55432" ]; then
    log "starting the scratch PostgreSQL 16 on :55432"
    su postgres -s /bin/sh -c "/usr/lib/postgresql/16/bin/pg_ctl -D /tmp/modoc-pg16/data -l /tmp/modoc-pg16/pg.log -o '-p 55432 -c listen_addresses=127.0.0.1 -c fsync=off' start" >/dev/null
    for _ in $(seq 1 30); do pg_isready -q -h "$PG_HOST" -p "$PG_PORT" && break; sleep 1; done
  fi
  pg_isready -q -h "$PG_HOST" -p "$PG_PORT" || die "PostgreSQL is not reachable on $PG_HOST:$PG_PORT"
fi

# --- 2. Build -------------------------------------------------------------------
if [ "${SKIP_BUILD:-0}" != "1" ]; then
  log "building backend jar (tests skipped)"
  (cd "$REPO_ROOT" && mvn -B -q -DskipTests ${E2E_MVN_ARGS:-} package) >"$RUN_DIR/build-backend.log" 2>&1 \
    || { tail -50 "$RUN_DIR/build-backend.log"; die "backend build failed"; }
  log "building frontend"
  (cd "$REPO_ROOT/frontend" && { [ -d node_modules ] || npm ci --no-audit --no-fund; } && npm run build) \
    >"$RUN_DIR/build-frontend.log" 2>&1 || { tail -50 "$RUN_DIR/build-frontend.log"; die "frontend build failed"; }
fi
JAR="$(ls -1 "$REPO_ROOT"/target/modocument-*.jar 2>/dev/null | grep -v -- '-plain' | head -1 || true)"
[ -n "$JAR" ] || die "no backend jar in target/ (run without SKIP_BUILD=1)"
[ -f "$REPO_ROOT/frontend/dist/index.html" ] || die "no frontend/dist (run without SKIP_BUILD=1)"
(cd "$SCRIPT_DIR" && { [ -d node_modules ] || npm ci --no-audit --no-fund >"$RUN_DIR/npm-e2e.log" 2>&1; }) \
  || die "npm ci in e2e/ failed (see $RUN_DIR/npm-e2e.log)"

# --- 3. Per-run secrets and database ------------------------------------------
# Generated here, exported to the processes of this run only, never written
# to a tracked file.
JWT_SECRET="$(openssl rand -base64 48)"
MFA_KEY="$(openssl rand -base64 48)"
OWNER_PASSWORD="$(openssl rand -hex 24)"
APP_DB_PASSWORD="${E2E_APP_DB_PASSWORD:-$(openssl rand -hex 24)}"
ADMIN_EMAIL="e2e-admin-${RUN_ID}@e2e.modocument.test"
ADMIN_PASSWORD="E2e-$(openssl rand -base64 18 | tr -d '/+=')-7q"
AI_KEY="e2e-$(openssl rand -hex 8)"   # the fake LLM ignores it; non-empty = "AI configured"
# The platform owner (ws21): BootstrapPlatformRunner creates the platform bank
# and exactly two PLATFORM_ADMIN accounts (forced password change + MFA).
PLATFORM_ADMIN_1_EMAIL="e2e-platform1-${RUN_ID}@e2e.modocument.test"
PLATFORM_ADMIN_2_EMAIL="e2e-platform2-${RUN_ID}@e2e.modocument.test"
PLATFORM_ADMIN_1_PASSWORD="E2p-$(openssl rand -base64 18 | tr -d '/+=,')-7q"
PLATFORM_ADMIN_2_PASSWORD="E2p-$(openssl rand -base64 18 | tr -d '/+=,')-8q"

log "creating database $DB_NAME (owner $DB_OWNER)"
psql_super -v owner="$DB_OWNER" -v pw="$OWNER_PASSWORD" <<'SQL'
CREATE ROLE :"owner" LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE PASSWORD :'pw';
SQL
psql_super -c "CREATE DATABASE \"$DB_NAME\" OWNER \"$DB_OWNER\""
DB_CREATED=1
psql_super -v app_pw="$APP_DB_PASSWORD" -v set_pw="${E2E_PG_SET_APP_PASSWORD:-0}" <<'SQL'
SELECT NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'modocument_app') AS create_app_role \gset
\if :create_app_role
CREATE ROLE modocument_app LOGIN NOBYPASSRLS PASSWORD :'app_pw';
\elif :set_pw
ALTER ROLE modocument_app WITH LOGIN NOBYPASSRLS PASSWORD :'app_pw';
\endif
SQL
psql_super -c "GRANT CONNECT ON DATABASE \"$DB_NAME\" TO modocument_app"

# --- 4. Fake LLM ---------------------------------------------------------------
LLM_PORT="${E2E_LLM_PORT:-$(free_port)}"
APP_PORT="${E2E_APP_PORT:-$(free_port)}"
WEB_PORT="${E2E_WEB_PORT:-$(free_port)}"

log "starting fake LLM on :$LLM_PORT"
FAKE_LLM_PORT="$LLM_PORT" FAKE_LLM_HOST=127.0.0.1 \
  setsid node "$SCRIPT_DIR/stubs/fake-llm.mjs" >"$RUN_DIR/fake-llm.log" 2>&1 &
PIDS+=("$!")

# --- 4b. Bank simulator (Wave 3; opt-in with E2E_BANK_SIM=1) -------------------
# The bank's database (a second database on the same local PostgreSQL, with
# its own least-privilege roles) and the REST stub. The SFTP server needs the
# compose file (e2e/bank-simulator/docker-compose.bank-sim.yml).
BANK_SIM_EXPORTS=()
if [ "${E2E_BANK_SIM:-0}" = "1" ]; then
  BANK_DB="bank_sim_${RUN_ID}"
  BANK_ADMIN="bank_sim_admin_${RUN_ID}"
  BANK_READER="bank_sim_reader_${RUN_ID}"
  BANK_WRITER="bank_sim_writer_${RUN_ID}"
  BANK_ADMIN_PASSWORD="$(openssl rand -hex 24)"
  BANK_READER_PASSWORD="$(openssl rand -hex 24)"
  BANK_WRITER_PASSWORD="$(openssl rand -hex 24)"
  BANK_CLIENT_ID="bank-sim-${RUN_ID}"
  BANK_CLIENT_SECRET="$(openssl rand -hex 24)"
  BANK_API_KEY="$(openssl rand -hex 24)"
  BANK_WEBHOOK_SECRET="$(openssl rand -hex 32)"
  log "creating bank simulator database $BANK_DB"
  psql_super -v role="$BANK_ADMIN" -v pw="$BANK_ADMIN_PASSWORD" <<'SQL'
CREATE ROLE :"role" LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE PASSWORD :'pw';
SQL
  psql_super -c "CREATE DATABASE \"$BANK_DB\" OWNER \"$BANK_ADMIN\""
  BANK_DB_CREATED=1
  psql -X -q -v ON_ERROR_STOP=1 -h "$PG_HOST" -p "$PG_PORT" -U "$PG_SUPERUSER" -d "$BANK_DB" \
    -v reader="$BANK_READER" -v reader_pw="$BANK_READER_PASSWORD" \
    -v writer="$BANK_WRITER" -v writer_pw="$BANK_WRITER_PASSWORD" \
    -f "$SCRIPT_DIR/bank-simulator/db/bank-sim.sql" >/dev/null
  psql -X -q -v ON_ERROR_STOP=1 -h "$PG_HOST" -p "$PG_PORT" -U "$PG_SUPERUSER" -d "$BANK_DB" \
    -c "GRANT ALL ON SCHEMA core, integration TO \"$BANK_ADMIN\"" \
    -c "GRANT ALL ON ALL TABLES IN SCHEMA core, integration TO \"$BANK_ADMIN\""
  BANK_REST_PORT="${E2E_BANK_REST_PORT:-$(free_port)}"
  log "starting bank simulator REST stub on :$BANK_REST_PORT"
  BANK_SIM_PORT="$BANK_REST_PORT" BANK_SIM_HOST=127.0.0.1 \
    BANK_SIM_CLIENT_ID="$BANK_CLIENT_ID" BANK_SIM_CLIENT_SECRET="$BANK_CLIENT_SECRET" \
    BANK_SIM_API_KEY="$BANK_API_KEY" BANK_SIM_WEBHOOK_SECRET="$BANK_WEBHOOK_SECRET" \
    setsid node "$SCRIPT_DIR/bank-simulator/rest-stub/server.mjs" >"$RUN_DIR/bank-rest.log" 2>&1 &
  PIDS+=("$!")
  BANK_SIM_EXPORTS=(
    "E2E_BANK_SIM_REST_URL=http://127.0.0.1:$BANK_REST_PORT"
    "E2E_BANK_SIM_DB_URL=postgres://$BANK_ADMIN:$BANK_ADMIN_PASSWORD@$PG_HOST:$PG_PORT/$BANK_DB"
    "E2E_BANK_SIM_DB_HOST_FOR_APP=$PG_HOST"
    "E2E_BANK_SIM_DB_PORT_FOR_APP=$PG_PORT"
    "E2E_BANK_SIM_DB_NAME=$BANK_DB"
    "E2E_BANK_SIM_DB_READER=$BANK_READER"
    "E2E_BANK_SIM_DB_READER_PASSWORD=$BANK_READER_PASSWORD"
    "E2E_BANK_SIM_DB_WRITER=$BANK_WRITER"
    "E2E_BANK_SIM_DB_WRITER_PASSWORD=$BANK_WRITER_PASSWORD"
    "E2E_BANK_SIM_CLIENT_ID=$BANK_CLIENT_ID"
    "E2E_BANK_SIM_CLIENT_SECRET=$BANK_CLIENT_SECRET"
    "E2E_BANK_SIM_API_KEY=$BANK_API_KEY"
    "E2E_BANK_SIM_WEBHOOK_SECRET=$BANK_WEBHOOK_SECRET"
  )
fi

# --- 5. Backend -----------------------------------------------------------------
# Production profile (no SPRING_PROFILES_ACTIVE): Flyway on PostgreSQL, RLS
# via the modocument_app role, bootstrap admin, MFA forced for admins.
# E2E-only relaxations (config, not code): the auth/MFA throttles are per
# client IP and the whole suite comes from 127.0.0.1, so they are raised; the
# AI read timeout is 5 s instead of 120 s so the fake LLM's `timeout` mode
# reaches the code fallback inside a test; everything else keeps its
# production default.
log "starting backend on :$APP_PORT (database role mode: $E2E_DB_APP_ROLE)"
(
  export MODOCUMENT_DB_URL="jdbc:postgresql://$PG_HOST:$PG_PORT/$DB_NAME?sslmode=disable"
  if [ "$E2E_DB_APP_ROLE" = "owner" ]; then
    export MODOCUMENT_DB_USERNAME="$DB_OWNER"
    export MODOCUMENT_DB_PASSWORD="$OWNER_PASSWORD"
  else
    export MODOCUMENT_DB_USERNAME=modocument_app
    export MODOCUMENT_DB_PASSWORD="$APP_DB_PASSWORD"
  fi
  export MODOCUMENT_FLYWAY_USERNAME="$DB_OWNER"
  export MODOCUMENT_FLYWAY_PASSWORD="$OWNER_PASSWORD"
  export MODOCUMENT_JWT_SECRET="$JWT_SECRET"
  export MODOCUMENT_MFA_KEY="$MFA_KEY"
  export MODOCUMENT_BOOTSTRAP_ADMIN_EMAIL="$ADMIN_EMAIL"
  export MODOCUMENT_BOOTSTRAP_ADMIN_PASSWORD="$ADMIN_PASSWORD"
  export MODOCUMENT_PLATFORM_ADMIN_EMAILS="$PLATFORM_ADMIN_1_EMAIL,$PLATFORM_ADMIN_2_EMAIL"
  export MODOCUMENT_PLATFORM_ADMIN_PASSWORDS="$PLATFORM_ADMIN_1_PASSWORD,$PLATFORM_ADMIN_2_PASSWORD"
  export MODOCUMENT_AI_BASE_URL="http://127.0.0.1:$LLM_PORT"
  export MODOCUMENT_AI_API_KEY="$AI_KEY"
  export MODOCUMENT_STORAGE_ROOT="$RUN_DIR/storage"
  export MODOCUMENT_CORS_ALLOWED_ORIGINS="http://127.0.0.1:$WEB_PORT"
  export SPRING_APPLICATION_JSON='{"modocument":{"security":{"rate-limit":{"auth":{"per-ip-max":1000,"per-email-max":200},"mfa":{"per-ip-max":200,"per-user-max":60}}},"ai":{"http":{"read-timeout":"5s"}}}}'
  exec setsid java ${E2E_JAVA_OPTS:--Xmx1536m} -jar "$JAR" \
    --server.port="$APP_PORT" --server.address=127.0.0.1
) >"$RUN_DIR/backend.log" 2>&1 &
PIDS+=("$!")
BACKEND_PID="$!"

# --- 6. SPA behind vite preview (proxy = frontend/vite.config.js server.proxy,
#        the same path list as frontend/nginx.conf) ----------------------------
log "serving frontend/dist on :$WEB_PORT (API proxied to :$APP_PORT)"
(
  cd "$REPO_ROOT/frontend"
  export VITE_BACKEND_URL="http://127.0.0.1:$APP_PORT"
  exec setsid npx --no-install vite preview --host 127.0.0.1 --port "$WEB_PORT" --strictPort
) >"$RUN_DIR/web.log" 2>&1 &
PIDS+=("$!")

# --- 7. Wait for health ------------------------------------------------------------
log "waiting for backend health"
deadline=$(( $(date +%s) + ${E2E_BOOT_TIMEOUT:-300} ))
until curl -fsS "http://127.0.0.1:$APP_PORT/actuator/health" 2>/dev/null | grep -q '"UP"'; do
  if ! kill -0 "$BACKEND_PID" 2>/dev/null; then
    tail -80 "$RUN_DIR/backend.log"; die "backend exited during startup"
  fi
  [ "$(date +%s)" -lt "$deadline" ] || { tail -80 "$RUN_DIR/backend.log"; die "backend not healthy in time"; }
  sleep 2
done
until curl -fsS "http://127.0.0.1:$WEB_PORT/" >/dev/null 2>&1; do
  [ "$(date +%s)" -lt "$deadline" ] || { tail -40 "$RUN_DIR/web.log"; die "frontend not reachable"; }
  sleep 1
done
log "stack is up: web http://127.0.0.1:$WEB_PORT  api :$APP_PORT  fake-llm :$LLM_PORT  db $DB_NAME"

# --- 8. Playwright -------------------------------------------------------------------
set +e
(
  cd "$SCRIPT_DIR"
  export E2E_BASE_URL="http://127.0.0.1:$WEB_PORT"
  export E2E_LLM_URL="http://127.0.0.1:$LLM_PORT"
  export E2E_DB_URL="postgres://$DB_OWNER:$OWNER_PASSWORD@$PG_HOST:$PG_PORT/$DB_NAME"
  export E2E_BOOTSTRAP_ADMIN_EMAIL="$ADMIN_EMAIL"
  export E2E_BOOTSTRAP_ADMIN_PASSWORD="$ADMIN_PASSWORD"
  export E2E_PLATFORM_ADMIN_1_EMAIL="$PLATFORM_ADMIN_1_EMAIL"
  export E2E_PLATFORM_ADMIN_1_PASSWORD="$PLATFORM_ADMIN_1_PASSWORD"
  export E2E_PLATFORM_ADMIN_2_EMAIL="$PLATFORM_ADMIN_2_EMAIL"
  export E2E_PLATFORM_ADMIN_2_PASSWORD="$PLATFORM_ADMIN_2_PASSWORD"
  export E2E_STATE_DIR="$RUN_DIR/state"
  export E2E_RUN_ID="$RUN_ID"
  export E2E_DB_APP_ROLE
  export E2E_BACKEND_LOG="$RUN_DIR/backend.log"
  for assignment in "${BANK_SIM_EXPORTS[@]}"; do export "${assignment?}"; done
  npx --no-install playwright test "$@"
)
TEST_STATUS=$?
set -e
log "playwright exit status: $TEST_STATUS"
exit "$TEST_STATUS"
