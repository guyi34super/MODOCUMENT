#!/usr/bin/env bash
# Fails with one clear message when any of the named variables is empty
# (ws24 release/deploy jobs: the GitHub environment's configuration
# variables, docs/release/runbook.md §7). Prints names only, never values.
#
#   scripts/release/require-env.sh NAME...
set -euo pipefail

missing=()
for name in "$@"; do
  if ! [[ "$name" =~ ^[A-Z][A-Z0-9_]*$ ]]; then
    echo "require-env: '$name' is not a variable name" >&2
    exit 2
  fi
  if [ -z "${!name:-}" ]; then
    missing+=("$name")
  fi
done
if [ "${#missing[@]}" -gt 0 ]; then
  echo "::error::Set these variables on the GitHub environment (Settings > Environments): ${missing[*]}" >&2
  exit 1
fi
echo "require-env: ${#} variable(s) set"
