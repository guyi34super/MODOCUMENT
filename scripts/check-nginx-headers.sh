#!/usr/bin/env bash
# E1 guard: every nginx location that serves content must emit the required
# security headers. nginx's add_header is NOT additive across levels — a
# location with any add_header of its own drops all inherited ones — so this
# checks each location block on its own:
#
#   * static locations (no proxy_pass) must carry the full SPA set, inline or
#     via `include snippets/<name>.conf` (resolved against frontend/<name>.conf);
#   * proxied locations (proxy_pass) must carry the API fallback set
#     (CSP, nosniff, X-Frame-Options, Referrer-Policy) for responses nginx
#     generates itself; the backend's own set is pinned by HeaderContractTest.
#     They must also never append to X-Forwarded-For and must clear the
#     client-sent `Forwarded`, X-Forwarded-Port/-Prefix/-Ssl (SEC-022).
#
# Also requires `server_tokens off`. Exits non-zero on any gap.
# Usage: scripts/check-nginx-headers.sh [path/to/nginx.conf]
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
conf="${1:-$root/frontend/nginx.conf}"
snippet_dir="$(dirname "$conf")"

static_required=(
  Content-Security-Policy
  X-Content-Type-Options
  X-Frame-Options
  Referrer-Policy
  Strict-Transport-Security
  Permissions-Policy
  Cross-Origin-Opener-Policy
)
proxy_required=(
  Content-Security-Policy
  X-Content-Type-Options
  X-Frame-Options
  Referrer-Policy
)
# Header values whose CONTENT matters, not just presence (static set).
csp_directives=("frame-ancestors 'none'" "object-src 'none'" "base-uri 'self'" "default-src 'none'")

[[ -f "$conf" ]] || { echo "check-nginx-headers: $conf not found" >&2; exit 2; }

# The config with comments stripped and `include snippets/…` inlined.
expand_includes() {
  awk -v dir="$snippet_dir" '
    { sub(/#.*/, "") }
    /^[[:space:]]*include[[:space:]]+[^;]*snippets\/[^;]*;/ {
      match($0, /snippets\/[^;[:space:]]+/)
      file = dir "/" substr($0, RSTART + 9, RLENGTH - 9)
      if ((getline probe < file) < 0) { print "__MISSING_INCLUDE__ " file; next }
      close(file)
      while ((getline line < file) > 0) { sub(/#.*/, "", line); print line }
      close(file)
      next
    }
    { print }
  ' "$conf"
}

# One record per location: "<line>\t<location args>\t<proxied 0|1>\t<body>";
# braces are tracked so nested blocks stay inside their location.
locations="$(expand_includes | awk '
  {
    line = $0
    if (depth_loc == 0 && match(line, /^[[:space:]]*location[[:space:]]+[^{]*\{/)) {
      header = substr(line, RSTART, RLENGTH)
      sub(/^[[:space:]]*location[[:space:]]+/, "", header); sub(/[[:space:]]*\{$/, "", header)
      start = NR; body = ""; depth_loc = 1
      line = substr(line, RSTART + RLENGTH)
    } else if (depth_loc == 0) { next }
    body = body " " line
    for (i = 1; i <= length(line); i++) {
      c = substr(line, i, 1)
      if (c == "{") depth_loc++
      else if (c == "}") {
        depth_loc--
        if (depth_loc == 0) {
          proxied = (body ~ /proxy_pass[[:space:]]/) ? 1 : 0
          gsub(/\t/, " ", body)
          printf "%d\t%s\t%d\t%s\n", start, header, proxied, body
          break
        }
      }
    }
  }
')"

status=0
fail() { echo "FAIL: $*" >&2; status=1; }

if expand_includes | grep -q '__MISSING_INCLUDE__'; then
  fail "$(expand_includes | grep '__MISSING_INCLUDE__' | sed 's/__MISSING_INCLUDE__ /snippet not found: /')"
fi
grep -Eq '^[[:space:]]*server_tokens[[:space:]]+off;' "$conf" || fail "server_tokens off is missing"
[[ -n "$locations" ]] || fail "no location blocks found in $conf"

checked=0
while IFS=$'\t' read -r line args proxied body; do
  [[ -n "${line:-}" ]] || continue
  checked=$((checked + 1))
  if [[ "$proxied" == 1 ]]; then required=("${proxy_required[@]}"); kind=proxied
  else required=("${static_required[@]}"); kind=static; fi
  for header in "${required[@]}"; do
    if ! grep -Eqi "add_header[[:space:]]+$header[[:space:]]+(\"[^\"]*\"|[^[:space:];\"]+)[[:space:]]+always[[:space:]]*;" <<<"$body"; then
      fail "line $line: $kind location '$args' lacks 'add_header $header ... always'"
    fi
  done
  if [[ "$kind" == proxied ]]; then
    # Forwarding headers: nginx must overwrite X-Forwarded-For (never append)
    # and clear the ones it does not set, or a client can pick the IP the app
    # trusts (Spring prefers RFC 7239 `Forwarded` over X-Forwarded-For).
    if grep -q 'proxy_add_x_forwarded_for' <<<"$body"; then
      fail "line $line: proxied location '$args' appends to X-Forwarded-For (\$proxy_add_x_forwarded_for)"
    fi
    for cleared in Forwarded X-Forwarded-Port X-Forwarded-Prefix X-Forwarded-Ssl; do
      if ! grep -Eqi "proxy_set_header[[:space:]]+$cleared[[:space:]]+\"\"[[:space:]]*;" <<<"$body"; then
        fail "line $line: proxied location '$args' does not clear client-sent '$cleared'"
      fi
    done
    # ws26: the ALB's client-certificate headers are an identity. Every
    # proxied location sets each of them explicitly: cleared (""), or — the
    # mTLS server only — the entrypoint's trusted-peer-only value
    # (${MODOCUMENT_NGX_MTLS_*}). Never the client's own $http_x_amzn_* value.
    if grep -Eqi 'http_x_amzn_mtls' <<<"$body"; then
      fail "line $line: proxied location '$args' forwards a client-sent X-Amzn-Mtls header directly"
    fi
    for mtls in X-Amzn-Mtls-Clientcert X-Amzn-Mtls-Clientcert-Serial-Number X-Amzn-Mtls-Clientcert-Issuer \
        X-Amzn-Mtls-Clientcert-Subject X-Amzn-Mtls-Clientcert-Validity X-Amzn-Mtls-Clientcert-Leaf; do
      if ! grep -Eqi "proxy_set_header[[:space:]]+$mtls[[:space:]]+(\"\"|\\\$\{MODOCUMENT_NGX_MTLS_[A-Z]+\})[[:space:]]*;" <<<"$body"; then
        fail "line $line: proxied location '$args' does not clear (or trusted-proxy-gate) '$mtls'"
      fi
    done
  fi
  if [[ "$kind" == static ]]; then
    csp="$(grep -Eoi "add_header[[:space:]]+Content-Security-Policy[[:space:]]+\"[^\"]*\"" <<<"$body" || true)"
    for directive in "${csp_directives[@]}"; do
      [[ "$csp" == *"$directive"* ]] || fail "line $line: CSP of '$args' lacks \"$directive\""
    done
    [[ "$csp" != *"'unsafe-eval'"* ]] || fail "line $line: CSP of '$args' allows 'unsafe-eval'"
    if grep -Eo "script-src[^;\"]*" <<<"$csp" | grep -q "'unsafe-inline'"; then
      fail "line $line: CSP of '$args' allows inline script"
    fi
  fi
done <<<"$locations"

if [[ $status -eq 0 ]]; then
  echo "check-nginx-headers: OK — $checked location(s) in ${conf#"$root"/} carry the required security headers"
fi
exit $status
