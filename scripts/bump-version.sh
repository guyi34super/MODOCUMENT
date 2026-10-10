#!/usr/bin/env bash
# Cut a release version (ws24): the ONLY supported way to change the version.
#
#   scripts/bump-version.sh 1.1.0
#
# 1. checks the tree is in sync now (scripts/check-version-sync.sh) and that
#    X.Y.Z is greater than the current version;
# 2. sets X.Y.Z as the project <version> in pom.xml (parent, dependency and
#    plugin versions are untouched) and in frontend/package.json +
#    package-lock.json (top-level and packages[""]);
# 3. turns the [Unreleased] entries of CHANGELOG.md into
#    "## [X.Y.Z] - YYYY-MM-DD" under a new, empty [Unreleased] heading, and
#    updates the compare links at the bottom when present;
# 4. re-runs the sync check.
#
# RELEASE_DATE=YYYY-MM-DD overrides today's UTC date. Nothing is committed or
# tagged: review the diff, commit "Release X.Y.Z", then tag vX.Y.Z-rc.1
# (docs/release/runbook.md).
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# shellcheck source=scripts/version-lib.sh
. "$root/scripts/version-lib.sh"

usage() {
  echo "usage: $0 X.Y.Z   (RELEASE_DATE=YYYY-MM-DD to override the date)" >&2
  exit 2
}

[ "$#" -eq 1 ] || usage
new="$1"
[[ "$new" =~ $SEMVER_RE ]] || { echo "bump-version: '$new' is not X.Y.Z" >&2; exit 2; }

date="${RELEASE_DATE:-$(date -u +%Y-%m-%d)}"
[[ "$date" =~ ^[0-9]{4}-[0-9]{2}-[0-9]{2}$ ]] \
  || { echo "bump-version: RELEASE_DATE '$date' is not YYYY-MM-DD" >&2; exit 2; }

pom="$root/pom.xml"
pkg="$root/frontend/package.json"
lock="$root/frontend/package-lock.json"
changelog="$root/CHANGELOG.md"

# Start from a consistent tree, so the bump can never paper over drift.
bash "$root/scripts/check-version-sync.sh" >/dev/null || {
  echo "bump-version: fix the version drift above first" >&2
  exit 1
}

old="$(pom_version "$pom")"
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
cp "$tmpdir/package.json" "$pkg"
cp "$tmpdir/package-lock.json" "$lock"
cp "$tmpdir/CHANGELOG.md" "$changelog"

bash "$root/scripts/check-version-sync.sh"
echo "bump-version: $old -> $new ($date). Next: review 'git diff', commit \"Release $new\", tag v$new-rc.1."
