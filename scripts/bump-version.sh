#!/usr/bin/env bash
# Cut a release version (ws24): the ONLY supported way to change the version.
#
#   scripts/bump-version.sh 1.1.0
#
# 1. checks the tree is in sync now (scripts/check-version-sync.sh) and that
#    X.Y.Z is greater than the current version;
# 2. sets X.Y.Z as the project <version> in pom.xml and agent/pom.xml (ws26;
#    parent, dependency and plugin versions are untouched) and in
#    frontend/package.json +
#    package-lock.json (top-level and packages[""]);
# 3. turns the [Unreleased] entries of CHANGELOG.md into
#    "## [X.Y.Z] - YYYY-MM-DD" under a new, empty [Unreleased] heading, and
#    updates the compare links at the bottom when present;
# 4. re-runs the sync check.
#
# RELEASE_DATE=YYYY-MM-DD overrides today's UTC date. Nothing is committed or
# tagged: review the diff, commit "Release X.Y.Z", then tag vX.Y.Z-rc.1
# (docs/release/runbook.md).
#
# A release candidate may be named too: `scripts/bump-version.sh 1.1.0-rc.1`
# bumps the files to 1.1.0 (they never carry "-rc"; the tag does) and names
# the tag to push. For a later candidate of the same release (files already
# at X.Y.Z) it changes nothing and only checks the tree is in sync.
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# shellcheck source=scripts/version-lib.sh
. "$root/scripts/version-lib.sh"

usage() {
  echo "usage: $0 X.Y.Z | X.Y.Z-rc.N   (RELEASE_DATE=YYYY-MM-DD to override the date)" >&2
  exit 2
}

[ "$#" -eq 1 ] || usage
new="$1"
rc=""
if [[ "$new" =~ ^([0-9]+\.[0-9]+\.[0-9]+)-rc\.[1-9][0-9]*$ ]]; then
  # The files carry X.Y.Z; "-rc.N" lives only in the tag.
  rc="$new"
  new="${BASH_REMATCH[1]}"
fi
[[ "$new" =~ $SEMVER_RE ]] || { echo "bump-version: '$1' is not X.Y.Z or X.Y.Z-rc.N" >&2; exit 2; }
tag="v${rc:-$new-rc.1}"

date="${RELEASE_DATE:-$(date -u +%Y-%m-%d)}"
[[ "$date" =~ ^[0-9]{4}-[0-9]{2}-[0-9]{2}$ ]] \
  || { echo "bump-version: RELEASE_DATE '$date' is not YYYY-MM-DD" >&2; exit 2; }

pom="$root/pom.xml"
agent_pom="$root/agent/pom.xml"
pkg="$root/frontend/package.json"
lock="$root/frontend/package-lock.json"
changelog="$root/CHANGELOG.md"

# Start from a consistent tree, so the bump can never paper over drift.
bash "$root/scripts/check-version-sync.sh" >/dev/null || {
  echo "bump-version: fix the version drift above first" >&2
  exit 1
}

old="$(pom_version "$pom")"
if [ -n "$rc" ] && [ "$new" = "$old" ]; then
  # A further candidate of the same release: the files already say X.Y.Z.
  bash "$root/scripts/check-version-sync.sh" "$tag"
  echo "bump-version: the tree is already at $new; nothing to change. Next: tag $tag on the release commit."
  exit 0
fi
if ! semver_gt "$new" "$old"; then
  echo "bump-version: $new must be greater than the current version $old" >&2
  exit 1
fi

# [Unreleased] must say what the release contains.
entries="$(awk '
  /^## \[Unreleased\]/ { in_section = 1; next }
  /^## \[/ { in_section = 0 }
  in_section && /^[-*] / { n++ }
  END { print n + 0 }' "$changelog")"
if [ "$entries" -eq 0 ]; then
  echo "bump-version: CHANGELOG.md [Unreleased] has no entries — describe the release first" >&2
  exit 1
fi

tmpdir="$(mktemp -d)"
trap 'rm -rf "$tmpdir"' EXIT

pom_set_version "$pom" "$new" > "$tmpdir/pom.xml"
if [ -f "$agent_pom" ]; then
  pom_set_version "$agent_pom" "$new" > "$tmpdir/agent-pom.xml"
  [ "$(pom_version "$tmpdir/agent-pom.xml")" = "$new" ] || { echo "bump-version: agent/pom.xml rewrite failed" >&2; exit 1; }
  changed="$(diff "$agent_pom" "$tmpdir/agent-pom.xml" | grep -c '^>' || true)"
  [ "$changed" -le 1 ] || { echo "bump-version: agent/pom.xml rewrite touched $changed lines (expected <= 1)" >&2; exit 1; }
fi
package_json_set_version "$pkg" "$new" > "$tmpdir/package.json"
package_lock_set_version "$lock" "$new" > "$tmpdir/package-lock.json"

awk -v new="$new" -v old="$old" -v date="$date" '
  /^## \[Unreleased\]/ && !headed {
    print
    print ""
    print "## [" new "] - " date
    headed = 1
    next
  }
  # Keep a Changelog compare links, when the file has them.
  /^\[Unreleased\]: .*\/compare\/v[^.]+\.[^.]+\.[^.]+\.\.\.HEAD$/ {
    base = $0
    sub(/^\[Unreleased\]: /, "", base)
    sub(/\/compare\/.*$/, "", base)
    print "[Unreleased]: " base "/compare/v" new "...HEAD"
    print "[" new "]: " base "/compare/v" old "...v" new
    next
  }
  { print }' "$changelog" > "$tmpdir/CHANGELOG.md"

# Sanity: every rewrite changed what it should, and only that.
[ "$(pom_version "$tmpdir/pom.xml")" = "$new" ] || { echo "bump-version: pom.xml rewrite failed" >&2; exit 1; }
[ "$(package_json_version "$tmpdir/package.json")" = "$new" ] || { echo "bump-version: package.json rewrite failed" >&2; exit 1; }
[ "$(package_lock_versions "$tmpdir/package-lock.json" | sort -u)" = "$new" ] || { echo "bump-version: package-lock.json rewrite failed" >&2; exit 1; }
for pair in "$pom:pom.xml" "$pkg:package.json" "$lock:package-lock.json"; do
  src="${pair%%:*}"; out="$tmpdir/${pair##*:}"
  changed="$(diff "$src" "$out" | grep -c '^>' || true)"
  max=1; [ "${pair##*:}" = "package-lock.json" ] && max=2
  if [ "$changed" -gt "$max" ]; then
    echo "bump-version: ${pair##*:} rewrite touched $changed lines (expected <= $max)" >&2
    exit 1
  fi
done

cp "$tmpdir/pom.xml" "$pom"
if [ -f "$agent_pom" ]; then
  cp "$tmpdir/agent-pom.xml" "$agent_pom"
fi
cp "$tmpdir/package.json" "$pkg"
cp "$tmpdir/package-lock.json" "$lock"
cp "$tmpdir/CHANGELOG.md" "$changelog"

bash "$root/scripts/check-version-sync.sh"
echo "bump-version: $old -> $new ($date). Next: review 'git diff', commit \"Release $new\", tag $tag."
