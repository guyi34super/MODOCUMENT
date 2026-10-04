#!/bin/sh
set -e

# Runs as the modocument user (the image sets USER; SUP-S04 — no root at any
# point of the container lifecycle). A named volume mounted at /app/wwwroot is
# seeded from the image with modocument ownership on first use; this mkdir
# only (re)creates the subdirectories inside it when something pruned them.
mkdir -p /app/wwwroot/uploads /app/wwwroot/exports

exec java -jar /app/app.jar
