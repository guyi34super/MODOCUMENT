#!/bin/sh
set -e

# Runs as the unprivileged modocument user (uid 10001 — the image sets USER;
# SUP-S04): no root at any point of the container lifecycle, so there is no
# runtime chown and no privilege drop here any more. Storage ownership is
# fixed at build time instead (see Dockerfile): a new named volume mounted at
# /app/wwwroot is seeded from the image with uid 10001 ownership.
storage_root="${MODOCUMENT_STORAGE_ROOT:-wwwroot}"

# (Re)create the upload/export folders inside the volume if something pruned
# them. Fail fast with the fix spelled out when the volume is not ours — a
# bind mount or a volume created by an older root-entrypoint image.
if ! mkdir -p "$storage_root/uploads" "$storage_root/exports" 2>/dev/null \
    || [ ! -w "$storage_root/uploads" ] || [ ! -w "$storage_root/exports" ]; then
    echo "modocument: storage root '$storage_root' is not writable by uid $(id -u)." >&2
    echo "  Fix once as root, e.g.: docker compose run --rm --user root --entrypoint" \
         "chown app -R 10001:10001 /app/wwwroot" >&2
    exit 1
fi

exec java -jar /app/app.jar
