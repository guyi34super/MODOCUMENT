#!/bin/sh
set -e

# Volume mounts replace image-owned wwwroot; ensure upload/export dirs are writable.
mkdir -p /app/wwwroot/uploads /app/wwwroot/exports
chown -R modocument:modocument /app/wwwroot

exec runuser -u modocument -- java -jar /app/app.jar
