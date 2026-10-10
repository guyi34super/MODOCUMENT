#!/usr/bin/env bash
# Version single source check (ws24). Fails when the release version differs
# between pom.xml, frontend/package.json (+ package-lock.json) and the newest
# released CHANGELOG.md heading, or when the changelog is malformed. Given a
# release tag (vX.Y.Z or vX.Y.Z-rc.N), the tag must name the same X.Y.Z.
#
#   scripts/check-version-sync.sh            # CI (ci.yml build-and-test)
#   scripts/check-version-sync.sh v1.1.0     # release.yml, before building
#   scripts/check-version-sync.sh v1.1.0-rc.2
#
# Exit codes: 0 in sync, 1 drift or malformed input, 2 usage error.
# Change the version only with scripts/bump-version.sh.
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# shellcheck source=scripts/version-lib.sh
. "$root/scripts/version-lib.sh"

if [ "$#" -gt 1 ]; then
  echo "usage: $0 [vX.Y.Z | vX.Y.Z-rc.N]" >&2
  exit 2
fi
tag="${1:-}"

failures=0
fail() {
  echo "version-sync: $*" >&2
  failures=$((failures + 1))
}

pom="$root/pom.xml"
pkg="$root/frontend/package.json"
lock="$root/frontend/package-lock.json"
changelog="$root/CHANGELOG.md"

for f in "$pom" "$pkg" "$lock" "$changelog"; do
  if [ ! -f "$f" ]; then
    echo "version-sync: missing ${f#"$root"/}" >&2
    exit 1
  fi
done

pom_v="$(pom_version "$pom")"
pkg_v="$(package_json_version "$pkg")"
lock_vs="$(package_lock_versions "$lock")"

if ! [[ "$pom_v" =~ $SEMVER_RE ]]; then
  fail "pom.xml project version '$pom_v' is not a release version X.Y.Z"
fi
if [ "$pkg_v" != "$pom_v" ]; then
  fail "frontend/package.json version '$pkg_v' != pom.xml '$pom_v'"
fi
if [ "$(printf '%s\n' "$lock_vs" | grep -c .)" -ne 2 ]; then
  fail "frontend/package-lock.json: expected a top-level and a packages[\"\"] version"
fi
while IFS= read -r lv; do
  if [ -n "$lv" ] && [ "$lv" != "$pom_v" ]; then
    fail "frontend/package-lock.json version '$lv' != pom.xml '$pom_v' (run npm install or scripts/bump-version.sh)"
  fi
done <<< "$lock_vs"

# --- CHANGELOG.md -------------------------------------------------------------
headings="$(changelog_headings "$changelog")"
unreleased_count="$(printf '%s\n' "$headings" | grep -c -x 'Unreleased' || true)"
if [ "$unreleased_count" -ne 1 ]; then
  fail "CHANGELOG.md needs exactly one '## [Unreleased]' heading (found $unreleased_count)"
elif [ "$(printf '%s\n' "$headings" | head -n 1)" != "Unreleased" ]; then
  fail "CHANGELOG.md: '## [Unreleased]' must be the first version heading"
fi

released="$(printf '%s\n' "$headings" | grep -v -x 'Unreleased' || true)"
dupes="$(printf '%s\n' "$released" | sed '/^$/d' | sort | uniq -d)"
if [ -n "$dupes" ]; then
  fail "CHANGELOG.md has duplicate release headings: $(echo "$dupes" | tr '\n' ' ')"
fi

top_line="$(changelog_top_release_line "$changelog")"
if [ -z "$top_line" ]; then
  fail "CHANGELOG.md has no released version heading ('## [X.Y.Z] - YYYY-MM-DD')"
else
  if ! [[ "$top_line" =~ ^##\ \[([0-9]+\.[0-9]+\.[0-9]+)\]\ -\ ([0-9]{4}-[0-9]{2}-[0-9]{2})$ ]]; then
    fail "CHANGELOG.md: newest release heading '$top_line' must read '## [X.Y.Z] - YYYY-MM-DD'"
  else
    top_v="${BASH_REMATCH[1]}"
    if [ "$top_v" != "$pom_v" ]; then
      fail "CHANGELOG.md newest release [$top_v] != pom.xml '$pom_v' (release with scripts/bump-version.sh)"
    fi
  fi
fi

# --- release tag --------------------------------------------------------------
if [ -n "$tag" ]; then
  if [[ "$tag" =~ ^v([0-9]+\.[0-9]+\.[0-9]+)(-rc\.[1-9][0-9]*)?$ ]]; then
    tag_v="${BASH_REMATCH[1]}"
    if [ "$tag_v" != "$pom_v" ]; then
      fail "tag $tag names $tag_v but pom.xml / package.json / CHANGELOG.md say '$pom_v'"
    fi
  else
    fail "tag '$tag' is not vX.Y.Z or vX.Y.Z-rc.N"
  fi
fi

if [ "$failures" -gt 0 ]; then
  echo "version-sync: FAILED ($failures problem(s))" >&2
  exit 1
fi
echo "version-sync: OK $pom_v (pom.xml, frontend/package.json, frontend/package-lock.json, CHANGELOG.md${tag:+, tag $tag})"
