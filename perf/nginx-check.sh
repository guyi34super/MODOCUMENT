#!/usr/bin/env bash
# Serves frontend/dist through the REAL frontend/nginx.conf (rendered like the
# image entrypoint does in edge mode; the local nginx binary, no Docker),
# proxying the API to a running backend, then:
#
#   1. checks the v1.2.0 static-delivery contract (test plan §7, C1-C6, C8):
#      gzip + immutable on /assets, a real 404 for a missing chunk, no-cache
#      on the shell and deep links, gzip on API JSON with no caching added,
#      the security headers on every location, the favicon;
#      results -> perf/results/<label>/nginx.json (exit 1 on any failure);
#   2. optionally runs a command against it with E2E_BASE_URL pointing at
#      nginx (e.g. perf/frontend-load.mjs, to measure what nginx actually
#      transfers), then stops nginx.
#
#   perf/nginx-check.sh                               # checks only
#   perf/nginx-check.sh -- node perf/frontend-load.mjs
#
# Upstream: NGINX_API_UPSTREAM (host:port), else the host:port of
# E2E_API_URL (set by e2e/run-local.sh), else 127.0.0.1:8080 (API checks then
# report the upstream as down). Run inside e2e/run-local.sh's E2E_RUN_CMD to
# get a live backend; see perf/README.md.
set -euo pipefail

PERF_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$PERF_DIR/.." && pwd)"
FE="$REPO_ROOT/frontend"
[ -f "$FE/dist/index.html" ] || { echo "nginx-check: no frontend/dist (npm run build)" >&2; exit 2; }
command -v nginx >/dev/null || { echo "nginx-check: nginx not installed" >&2; exit 2; }

upstream="${NGINX_API_UPSTREAM:-}"
if [ -z "$upstream" ] && [ -n "${E2E_API_URL:-}" ]; then
  upstream="$(node -e 'const u=new URL(process.argv[1]);console.log(u.host)' "$E2E_API_URL")"
fi
upstream="${upstream:-127.0.0.1:8080}"
port="${NGINX_PORT:-$(node -e "const s=require('net').createServer();s.listen(0,'127.0.0.1',()=>{console.log(s.address().port);s.close()})")}"
label="${PERF_LABEL:-adhoc}"
out="${PERF_OUT_DIR:-$PERF_DIR/results/$label}"
mkdir -p "$out"

P="$(mktemp -d "${TMPDIR:-/tmp}/modoc-nginx.XXXXXX")"
mkdir -p "$P/conf/snippets" "$P/conf/conf.d" "$P/html" "$P/logs" "$P/tmp"
export MODOCUMENT_API_UPSTREAM="$upstream" MODOCUMENT_PUBLIC_HOST="127.0.0.1:$port"
export MODOCUMENT_NGX_FORWARDED_FOR='$remote_addr' MODOCUMENT_NGX_FORWARDED_PROTO='$scheme'
export MODOCUMENT_NGX_MTLS_SERIAL='""' MODOCUMENT_NGX_MTLS_ISSUER='""' MODOCUMENT_NGX_MTLS_SUBJECT='""' \
  MODOCUMENT_NGX_MTLS_VALIDITY='""' MODOCUMENT_NGX_MTLS_LEAF='""'
vars='$MODOCUMENT_API_UPSTREAM $MODOCUMENT_PUBLIC_HOST $MODOCUMENT_NGX_FORWARDED_FOR $MODOCUMENT_NGX_FORWARDED_PROTO $MODOCUMENT_NGX_MTLS_SERIAL $MODOCUMENT_NGX_MTLS_ISSUER $MODOCUMENT_NGX_MTLS_SUBJECT $MODOCUMENT_NGX_MTLS_VALIDITY $MODOCUMENT_NGX_MTLS_LEAF'
# Only the listen ports and the html root differ from the image.
envsubst "$vars" <"$FE/nginx.conf" \
  | sed -e "s#/usr/share/nginx/html#$P/html#g" \
        -e "s/listen 8080;/listen 127.0.0.1:$port;/" \
        -e "s/listen 8443;/listen 127.0.0.1:$((port + 1));/" \
  >"$P/conf/conf.d/default.conf"
cp "$FE/security-headers.conf" "$P/conf/snippets/security-headers.conf"
cp -r "$FE/dist/." "$P/html/"
cp /etc/nginx/mime.types "$P/conf/"
cat >"$P/conf/nginx.conf" <<EOF
worker_processes 1;
pid $P/nginx.pid;
error_log $P/logs/error.log;
events {}
http {
  include mime.types;
  default_type application/octet-stream;
  access_log off;
  client_body_temp_path $P/tmp; proxy_temp_path $P/tmp; fastcgi_temp_path $P/tmp; uwsgi_temp_path $P/tmp; scgi_temp_path $P/tmp;
  include conf.d/default.conf;
}
EOF
[ "$(id -u)" = 0 ] && sed -i '1i user root;' "$P/conf/nginx.conf"
nginx -q -t -p "$P" -c "$P/conf/nginx.conf"
nginx -p "$P" -c "$P/conf/nginx.conf"
stop() { nginx -p "$P" -c "$P/conf/nginx.conf" -s stop 2>/dev/null || true; rm -rf "$P"; }
trap stop EXIT
for _ in $(seq 1 20); do curl -fsS -o /dev/null "http://127.0.0.1:$port/" 2>/dev/null && break; sleep 0.2; done

status=0
BASE="http://127.0.0.1:$port" PERF_NGINX_HTML="$P/html" node "$PERF_DIR/nginx-headers.mjs" "$out/nginx.json" || status=$?

if [ "${1:-}" = "--" ]; then
  shift
  echo "[perf] running against nginx at http://127.0.0.1:$port: $*"
  (cd "$REPO_ROOT" && E2E_BASE_URL="http://127.0.0.1:$port" "$@")
fi
exit "$status"
