#!/usr/bin/env bash
# Throwaway PKI for the LOCAL mTLS listener (development only; Wave 4).
#
# docker-compose.mtls-dev.yml runs an nginx with `ssl_verify_client optional`
# in front of the app, standing in for the AWS ALB's mTLS listener
# (docs/integration/developer-guide.md, "Local mTLS listener"). This script
# makes the key material it needs, at runtime, outside git:
#
#   ca.crt / ca.key           a dev CA (never mounted; ca.key stays 0600)
#   server.crt / server.key   the listener's certificate: localhost,
#                             127.0.0.1, host.docker.internal
#   clients/<name>/           one client certificate per name, laid out as a
#                             Connector Agent secret folder:
#                             certificatePem + privateKeyPem (PKCS#8),
#                             plus <name>.crt / <name>.key next to it
#
# Usage:
#   scripts/dev-mtls-certs.sh                 # CA (once), server cert, client "agent"
#   scripts/dev-mtls-certs.sh client <name>   # one more client certificate
#   scripts/dev-mtls-certs.sh clean           # delete everything
#
# Output: $MODOCUMENT_MTLS_DEV_DIR (default <repo>/.mtls-dev, ignored by git
# and Docker). Validity: $MODOCUMENT_MTLS_DEV_DAYS days (default 30). Keys
# are EC P-256. NEVER use any of this outside a developer machine.
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
out="${MODOCUMENT_MTLS_DEV_DIR:-$root/.mtls-dev}"
days="${MODOCUMENT_MTLS_DEV_DAYS:-30}"

die() { echo "dev-mtls-certs: $*" >&2; exit 1; }
command -v openssl >/dev/null || die "openssl is required"
case "$days" in '' | *[!0-9]*) die "MODOCUMENT_MTLS_DEV_DAYS must be a number of days" ;; esac

umask 077

new_key() {
  openssl genpkey -algorithm EC -pkeyopt ec_paramgen_curve:P-256 -out "$1" 2>/dev/null
}

ensure_ca() {
  mkdir -p "$out"
  if [ -s "$out/ca.crt" ] && [ -s "$out/ca.key" ]; then
    return
  fi
  new_key "$out/ca.key"
  openssl req -x509 -new -key "$out/ca.key" -sha256 -days "$days" \
    -subj "/O=MoDocument development/CN=MoDocument DEV mTLS CA - local only" \
    -addext "basicConstraints=critical,CA:TRUE,pathlen:0" \
    -addext "keyUsage=critical,keyCertSign,cRLSign" \
    -out "$out/ca.crt"
  chmod 0644 "$out/ca.crt"
  echo "dev CA:      $out/ca.crt"
}

# sign <key> <crt> <subject> <extensions>
sign() {
  local csr ext
  csr="$(mktemp)"
  ext="$(mktemp)"
  printf '%s\n' "$4" >"$ext"
  openssl req -new -key "$1" -subj "$3" -out "$csr"
  openssl x509 -req -in "$csr" -CA "$out/ca.crt" -CAkey "$out/ca.key" -CAcreateserial \
    -days "$days" -sha256 -extfile "$ext" -out "$2" 2>/dev/null
  rm -f "$csr" "$ext"
  chmod 0644 "$2"
}

fingerprint() {
  openssl x509 -in "$1" -outform DER | sha256sum | cut -d' ' -f1
}

server() {
  new_key "$out/server.key"
  sign "$out/server.key" "$out/server.crt" "/O=MoDocument development/CN=localhost" \
    "basicConstraints=critical,CA:FALSE
keyUsage=critical,digitalSignature
extendedKeyUsage=serverAuth
subjectAltName=DNS:localhost,IP:127.0.0.1,DNS:host.docker.internal"
  # The nginx container (uid 101) reads this through a bind mount. It is a
  # throwaway key for a loopback-only listener, so world-readable is fine.
  chmod 0644 "$out/server.key"
  echo "server:      $out/server.crt (localhost, 127.0.0.1, host.docker.internal)"
}

client() {
  local name="$1" dir
  case "$name" in
    '' | *[!A-Za-z0-9._-]* | .*) die "client name: letters, digits, . _ - (got '$name')" ;;
  esac
  dir="$out/clients/$name"
  mkdir -p "$dir"
  new_key "$dir/$name.key"
  sign "$dir/$name.key" "$dir/$name.crt" "/O=MoDocument development/CN=$name" \
    "basicConstraints=critical,CA:FALSE
keyUsage=critical,digitalSignature
extendedKeyUsage=clientAuth"
  # The Connector Agent's local secret folder layout (agent/README.md,
  # "Authentication"): mount or point MODOCUMENT_SECRETS_DIR at clients/.
  cp "$dir/$name.crt" "$dir/certificatePem"
  cp "$dir/$name.key" "$dir/privateKeyPem"
  chmod 0644 "$dir/certificatePem"
  echo "client:      $dir/$name.crt"
  echo "  fingerprint (SHA-256, what MoDocument stores): $(fingerprint "$dir/$name.crt")"
}

case "${1:-all}" in
  all)
    ensure_ca
    server
    client agent
    ;;
  client)
    [ -s "$out/ca.key" ] || die "no dev CA yet: run $0 first"
    client "${2:-}"
    ;;
  clean)
    rm -rf "$out"
    echo "removed $out"
    ;;
  *)
    die "usage: $0 [all | client <name> | clean]"
    ;;
esac
