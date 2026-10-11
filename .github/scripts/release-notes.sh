#!/usr/bin/env bash
# Prints the CHANGELOG.md section for the given version (the text under "## [x.y.z]" up to the next "## ").
# Exits non-zero if the section is missing or empty.
set -euo pipefail

version="${1:?usage: release-notes.sh VERSION}"
changelog="${CHANGELOG:-CHANGELOG.md}"

notes="$(awk -v v="$version" '
  /^## \[/ {
    if (found) exit
    if (index($0, "## [" v "]") == 1) { found = 1; next }
  }
  found { print }
' "$changelog" | sed -e :a -e '/^[[:space:]]*$/{$d;N;ba' -e '}' | sed '/./,$!d')"

[[ -n "$notes" ]] || { echo "no CHANGELOG section for $version" >&2; exit 1; }
printf '%s\n' "$notes"
