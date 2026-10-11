# shellcheck shell=bash
# Shared helpers for scripts/check-version-sync.sh and scripts/bump-version.sh
# (ws24). Sourced, never executed. Plain awk/sed only: no node, python or
# Maven needed, so the CI check costs nothing and runs anywhere with bash.
#
# The version lives in exactly these places:
#   pom.xml                      <project><version> (not the parent's)
#   frontend/package.json        top-level "version"
#   frontend/package-lock.json   top-level "version" and packages[""].version
#   CHANGELOG.md                 the newest "## [X.Y.Z] - YYYY-MM-DD" heading

SEMVER_RE='^[0-9]+\.[0-9]+\.[0-9]+$'

# Standalone Maven projects next to the app that carry the same version
# (each only when present): the Connector Agent (ws26) and connector-core,
# the connector library the app and the agent share.
SIBLING_POMS="agent/pom.xml connector-core/pom.xml"

# Prints the project version of a pom: the first <version> outside the
# <parent> block and outside XML comments.
pom_version() {
  awk '
    function strip_comments(s,   out, i) {
      out = ""
      while (length(s) > 0) {
        if (incomment) {
          i = index(s, "-->")
          if (i == 0) { return out }
          s = substr(s, i + 3); incomment = 0
        } else {
          i = index(s, "<!--")
          if (i == 0) { return out s }
          out = out substr(s, 1, i - 1); s = substr(s, i + 4); incomment = 1
        }
      }
      return out
    }
    {
      line = strip_comments($0)
      if (line ~ /<parent>/) { inparent = 1 }
      if (inparent) { if (line ~ /<\/parent>/) { inparent = 0 }; next }
      if (match(line, /<version>[^<]*<\/version>/)) {
        print substr(line, RSTART + 9, RLENGTH - 19)
        exit
      }
    }' "$1"
}

# Rewrites the project version of a pom (same element pom_version reads) to
# $2, leaving every other <version> (parent, dependencies, plugins) alone.
pom_set_version() {
  awk -v v="$2" '
    function strip_comments(s,   out, i) {
      out = ""
      while (length(s) > 0) {
        if (incomment) {
          i = index(s, "-->")
          if (i == 0) { return out }
          s = substr(s, i + 3); incomment = 0
        } else {
          i = index(s, "<!--")
          if (i == 0) { return out s }
          out = out substr(s, 1, i - 1); s = substr(s, i + 4); incomment = 1
        }
      }
      return out
    }
    {
      line = strip_comments($0)
      if (!done && line ~ /<parent>/) { inparent = 1 }
      if (!done && !inparent && line ~ /<version>[^<]*<\/version>/) {
        sub(/<version>[^<]*<\/version>/, "<version>" v "</version>")
        done = 1
      } else if (inparent && line ~ /<\/parent>/) {
        inparent = 0
      }
      print
    }' "$1"
}

# Top-level "version" of an npm-formatted package.json (2-space indent).
package_json_version() {
  sed -n 's/^  "version": "\([^"]*\)",\{0,1\}$/\1/p' "$1" | head -n 1
}

# The two versions of an npm lockfile (v2/v3): the top-level one and the
# root package's (packages[""]), one per line.
package_lock_versions() {
  awk '
    /^  "version": "/ && !top { top = 1; print_version() }
    /^    "": \{/ { inroot = 1; next }
    inroot && /^      "version": "/ { print_version(); inroot = 0 }
    inroot && /^    \}/ { inroot = 0 }
    function print_version(   s) {
      s = $0; sub(/^ *"version": "/, "", s); sub(/".*$/, "", s); print s
    }' "$1"
}

# Rewrites the top-level "version" of package.json to $2.
package_json_set_version() {
  awk -v v="$2" '
    !done && /^  "version": "[^"]*"/ {
      sub(/"version": "[^"]*"/, "\"version\": \"" v "\""); done = 1
    }
    { print }' "$1"
}

# Rewrites both versions of an npm lockfile to $2.
package_lock_set_version() {
  awk -v v="$2" '
    !top && /^  "version": "[^"]*"/ {
      sub(/"version": "[^"]*"/, "\"version\": \"" v "\""); top = 1
    }
    /^    "": \{/ { inroot = 1 }
    inroot && /^      "version": "[^"]*"/ {
      sub(/"version": "[^"]*"/, "\"version\": \"" v "\""); inroot = 0
    }
    inroot && /^    \}/ { inroot = 0 }
    { print }' "$1"
}

# Every "## [...]" heading of a changelog, one bracket content per line, in
# file order ("Unreleased" included).
changelog_headings() {
  sed -n 's/^## \[\([^]]*\)\].*$/\1/p' "$1"
}

# The newest released heading line ("## [X.Y.Z] - YYYY-MM-DD"), i.e. the
# first "## [" heading that is not [Unreleased].
changelog_top_release_line() {
  grep -E '^## \[' "$1" | grep -v -E '^## \[Unreleased\]' | head -n 1
}

# Succeeds when $1 > $2 (both X.Y.Z).
semver_gt() {
  local IFS=.
  # shellcheck disable=SC2206
  local a=($1) b=($2)
  local i
  for i in 0 1 2; do
    if ((10#${a[i]} > 10#${b[i]})); then return 0; fi
    if ((10#${a[i]} < 10#${b[i]})); then return 1; fi
  done
  return 1
}
