#!/usr/bin/env bash
# Prints the CHANGELOG.md section of one release (ws24): the lines between
# "## [X.Y.Z] - YYYY-MM-DD" and the next "## [" heading, without the heading,
# the surrounding blank lines or link-reference definitions — the body of
# the GitHub Release that release.yml creates.
#
#   scripts/release/changelog-section.sh X.Y.Z [CHANGELOG.md]
#
# Exit codes: 0 printed, 1 no such (non-empty) section, 2 usage error.
set -euo pipefail

if [ "$#" -lt 1 ] || [ "$#" -gt 2 ]; then
  echo "usage: $0 X.Y.Z [CHANGELOG.md]" >&2
  exit 2
fi
version="$1"
root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
changelog="${2:-$root/CHANGELOG.md}"

if ! [[ "$version" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
  echo "changelog-section: '$version' is not X.Y.Z" >&2
  exit 2
fi
if [ ! -f "$changelog" ]; then
  echo "changelog-section: $changelog not found" >&2
  exit 2
fi

section="$(awk -v heading="## [$version] - " '
  index($0, heading) == 1 { found = 1; next }
  found && /^## \[/ { exit }
  found && /^\[[^]]*\]: / { next }
  found { lines[++n] = $0 }
  END {
    first = 1
    while (first <= n && lines[first] ~ /^[ \t]*$/) first++
    last = n
    while (last >= first && lines[last] ~ /^[ \t]*$/) last--
    for (i = first; i <= last; i++) print lines[i]
  }
' "$changelog")"

if [ -z "$section" ]; then
  echo "changelog-section: $changelog has no notes for [$version]" >&2
  exit 1
fi
printf '%s\n' "$section"
