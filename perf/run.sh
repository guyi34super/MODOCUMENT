#!/usr/bin/env bash
# Runs the whole perf measurement set and writes perf/results/<label>/*.json.
#
#   perf/run.sh baseline                 # build, stack, seed, API, frontend, footprint
#   PERF_TESTS=1 perf/run.sh wave2       # ... plus the backend + Vitest suite timings
#   PERF_SKIP_BUILD=1 perf/run.sh x      # reuse target/*.jar and frontend/dist
#   node perf/compare.mjs baseline wave2 # before/after table
#
# Knobs: PERF_JOBS (50) PERF_ROWS (1000) PERF_ITERATIONS (20) PERF_WARMUP (3)
# PERF_RUNS (3 cold browser loads) PERF_SEED_API_BUDGET_S (600).
# The stack is e2e/run-local.sh (scratch PostgreSQL on 127.0.0.1:55432, fresh
# database per run, torn down at the end); see perf/README.md.
set -euo pipefail

PERF_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$PERF_DIR/.." && pwd)"
LABEL="${1:?usage: perf/run.sh <label>}"
export PERF_LABEL="$LABEL"
export PERF_OUT_DIR="$PERF_DIR/results/$LABEL"
mkdir -p "$PERF_OUT_DIR"
log() { printf '\033[1;35m[perf]\033[0m %s\n' "$*"; }

{ echo "label=$LABEL"; echo "commit=$(git -C "$REPO_ROOT" rev-parse HEAD)"; echo "start=$(date -u +%FT%TZ)"; echo "uptime_start=$(uptime)"; } >"$PERF_OUT_DIR/run.meta"

# --- 1. Builds (timed) -----------------------------------------------------------
if [ "${PERF_SKIP_BUILD:-0}" != "1" ]; then
  log "mvn -B -DskipTests package"
  t0=$(date +%s)
  (cd "$REPO_ROOT" && mvn -B -q -DskipTests package) >"$PERF_OUT_DIR/build-backend.log" 2>&1
  echo "backend_package_seconds=$(( $(date +%s) - t0 ))" >>"$PERF_OUT_DIR/run.meta"
  [ -d "$REPO_ROOT/frontend/node_modules" ] || (cd "$REPO_ROOT/frontend" && npm ci --no-audit --no-fund)
  log "npm run build (chunk sizes)"
  node "$PERF_DIR/build-sizes.mjs"
else
  PERF_SKIP_FRONTEND_BUILD=1 node "$PERF_DIR/build-sizes.mjs"
fi

# --- 2. Stack + API/frontend/footprint ------------------------------------------------
log "starting the local stack (e2e/run-local.sh) and running the benchmarks"
SKIP_BUILD=1 E2E_RUN_CMD="node perf/seed.mjs && node perf/api-bench.mjs && node perf/frontend-load.mjs && node perf/footprint.mjs && cp \"\$E2E_BACKEND_LOG\" \"$PERF_OUT_DIR/backend.log\"" \
  "$REPO_ROOT/e2e/run-local.sh"

# --- 3. Test suites (opt-in: they take ~10 minutes) ----------------------------------
if [ "${PERF_TESTS:-0}" = "1" ]; then
  log "mvn -B verify (timed)"
  t0=$(date +%s)
  # A red suite is recorded, not fatal: the timing is still worth keeping.
  verify_rc=0
  (cd "$REPO_ROOT" && mvn -B verify) >"$PERF_OUT_DIR/mvn-verify.log" 2>&1 || verify_rc=$?
  echo "verify_exit=$verify_rc" >>"$PERF_OUT_DIR/run.meta"
  wall=$(( $(date +%s) - t0 ))
  node "$PERF_DIR/test-suite.mjs" "$PERF_OUT_DIR/mvn-verify.log" "$REPO_ROOT/target/surefire-reports" "$wall"
  log "vitest run (timed)"
  t0=$(date +%s)
  vitest_rc=0
  (cd "$REPO_ROOT/frontend" && npx vitest run) >"$PERF_OUT_DIR/vitest.log" 2>&1 || vitest_rc=$?
  echo "vitest_wall_seconds=$(( $(date +%s) - t0 ))" >>"$PERF_OUT_DIR/run.meta"
  echo "vitest_exit=$vitest_rc" >>"$PERF_OUT_DIR/run.meta"
  grep -E "Test Files|Tests |Duration" "$PERF_OUT_DIR/vitest.log" | sed 's/\x1b\[[0-9;]*m//g' >>"$PERF_OUT_DIR/run.meta" || true
fi

{ echo "end=$(date -u +%FT%TZ)"; echo "uptime_end=$(uptime)"; } >>"$PERF_OUT_DIR/run.meta"
# The backend log holds the run's throwaway user emails only (no secrets),
# but it is large: keep it out of git (perf/.gitignore).
log "done: $PERF_OUT_DIR"
