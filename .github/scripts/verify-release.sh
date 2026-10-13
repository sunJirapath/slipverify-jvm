#!/usr/bin/env bash
# Fails unless the pushed tag is a releasable version of the commit it points at.
# Inputs (environment): TAG (e.g. v1.2.3), COMMIT (the tagged commit), BRANCH (default branch, e.g. main).
set -euo pipefail

fail() {
  echo "::error::$*"
  exit 1
}

[[ "$TAG" =~ ^v[0-9]+\.[0-9]+\.[0-9]+(-[0-9A-Za-z.]+)?$ ]] || fail "tag '$TAG' is not vMAJOR.MINOR.PATCH[-suffix]"
version="${TAG#v}"

gradle_version="$(sed -n 's/^VERSION_NAME=//p' gradle.properties | tr -d '\r')"
[[ -n "$gradle_version" ]] || fail "VERSION_NAME not found in gradle.properties"
[[ "$gradle_version" != *SNAPSHOT* ]] || fail "VERSION_NAME is '$gradle_version': a SNAPSHOT cannot be released"
[[ "$version" == "$gradle_version" ]] || fail "tag version '$version' does not match VERSION_NAME '$gradle_version' in gradle.properties"

git merge-base --is-ancestor "$COMMIT" "origin/$BRANCH" || fail "tagged commit is not on $BRANCH"

"$(dirname "$0")/release-notes.sh" "$version" >/dev/null || fail "CHANGELOG.md has no non-empty '## [$version]' section"
echo "Releasing $version"
