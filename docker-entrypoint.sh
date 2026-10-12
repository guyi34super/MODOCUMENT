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

# JVM flags (ws29). Defaults go FIRST in JAVA_TOOL_OPTIONS so anything the
# deployment already sets there wins (for -XX flags the last occurrence
# counts); JAVA_OPTS lands on the command line and beats both.
#   MaxRAMPercentage=70  heap sized from the container memory limit, leaving
#                        ~30% for metaspace, threads, direct buffers and the
#                        soffice/tesseract children
#   UseG1GC              explicit: below 2 CPUs / 1792 MiB the JVM would
#                        otherwise pick SerialGC
#   ExitOnOutOfMemoryError  die and let ECS replace the task instead of
#                        limping on in an undefined state
jvm_defaults="-XX:MaxRAMPercentage=70 -XX:+UseG1GC -XX:+ExitOnOutOfMemoryError"
export JAVA_TOOL_OPTIONS="$jvm_defaults${JAVA_TOOL_OPTIONS:+ $JAVA_TOOL_OPTIONS}"

# AppCDS archive from the image build's training run (Dockerfile). Missing
# (an image built without it) or disabled with MODOCUMENT_CDS=off: start
# without it. A stale archive (different JVM or class path) is ignored by
# the JVM itself (-Xshare:auto, the default) with a warning, never fatal.
cds=""
if [ "${MODOCUMENT_CDS:-on}" != "off" ] && [ -f /app/app.jsa ]; then
    cds="-XX:SharedArchiveFile=/app/app.jsa"
fi

# shellcheck disable=SC2086 # JAVA_OPTS / cds are deliberately word-split
exec java $cds ${JAVA_OPTS:-} -jar /app/app.jar
