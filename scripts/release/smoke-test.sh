#!/usr/bin/env bash
# Post-deploy smoke test (ws24): the public synthetic probe must report the
# released version with the database reachable, several times in a row (so
# no task of the previous version is still answering behind the ALB).
#
#   scripts/release/smoke-test.sh <base-url> <X.Y.Z>
#
#   base-url  https://… origin the users reach (the frontend proxies /api)
#   X.Y.Z     the pom version the probe reports (an rc tag vX.Y.Z-rc.N runs X.Y.Z)
#
# GET <base-url>/api/probe/synthetic answers {"status","version","db","checkedAt"}
# (public, rate-limited to 60/min per IP). Tunables: SMOKE_ATTEMPTS (40),
# SMOKE_INTERVAL_SECONDS (10), SMOKE_CONSECUTIVE (3). SMOKE_ALLOW_HTTP=1
# permits http:// (local dry runs only).
set -euo pipefail

if [ "$#" -ne 2 ]; then
  echo "usage: $0 <https://base-url> <X.Y.Z>" >&2
  exit 2
fi
base="${1%/}"
expected="$2"
attempts="${SMOKE_ATTEMPTS:-40}"
interval="${SMOKE_INTERVAL_SECONDS:-10}"
needed="${SMOKE_CONSECUTIVE:-3}"

if ! [[ "$expected" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
  echo "smoke-test: expected version '$expected' is not X.Y.Z" >&2
  exit 2
fi
if ! [[ "$base" =~ ^https://[A-Za-z0-9.-]+(:[0-9]+)?$ ]] \
  && ! { [ "${SMOKE_ALLOW_HTTP:-0}" = "1" ] && [[ "$base" =~ ^http://[A-Za-z0-9.-]+(:[0-9]+)?$ ]]; }; then
  echo "smoke-test: base url '$base' must be https://host[:port] (no path)" >&2
  exit 2
fi

url="$base/api/probe/synthetic"
streak=0
for attempt in $(seq 1 "$attempts"); do
  body="$(curl --silent --show-error --max-time 10 --proto '=https,http' "$url" 2>&1 || true)"
  status="$(jq -r '.status // empty' <<< "$body" 2>/dev/null || true)"
  version="$(jq -r '.version // empty' <<< "$body" 2>/dev/null || true)"
  db="$(jq -r '.db // empty' <<< "$body" 2>/dev/null || true)"
  if [ "$status" = "UP" ] && [ "$db" = "true" ] && [ "$version" = "$expected" ]; then
    streak=$((streak + 1))
    echo "smoke-test: attempt $attempt: UP, version $version ($streak/$needed)"
    if [ "$streak" -ge "$needed" ]; then
      echo "smoke-test: OK — $url reports $expected"
      exit 0
    fi
  else
    streak=0
    echo "smoke-test: attempt $attempt: status='${status:-?}' db='${db:-?}' version='${version:-?}'" \
      "(want UP/true/$expected)"
  fi
  sleep "$interval"
done
echo "smoke-test: FAILED — $url never reported version $expected $needed times in a row" >&2
exit 1
