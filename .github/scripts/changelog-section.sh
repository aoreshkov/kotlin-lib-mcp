#!/usr/bin/env bash
# Prints one version's section of CHANGELOG.md, without its heading. Fails if the section is
# missing or empty, so a release cannot go out with no notes.
#
# Usage: changelog-section.sh <version>    (no leading "v")
set -euo pipefail

version="$1"
section="$(awk -v heading="## [${version}]" '
  index($0, heading) == 1 { found = 1; next }
  found && (/^## \[/ || /^\[[^]]+\]: /) { exit }
  found
' CHANGELOG.md)"

if [[ -z "${section//[[:space:]]/}" ]]; then
  echo "CHANGELOG.md has no entry for ${version}" >&2
  exit 1
fi
printf '%s\n' "$section"
