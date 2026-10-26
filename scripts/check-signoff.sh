#!/usr/bin/env bash
# Every commit in a range must carry a Developer Certificate of Origin sign-off.
#
# CONTRIBUTING.md describes the sign-off as a merge gate, and the organization's
# DCO.md (ezclassify/ezclassify) carries the certificate text. This script is
# what makes the gate real; CI runs it over the commits a pull request adds.
#
# Usage: check-signoff.sh <base-ref> <head-ref>
set -euo pipefail

if [ "$#" -ne 2 ]; then
  echo "usage: check-signoff.sh <base-ref> <head-ref>" >&2
  exit 2
fi

base=$1
head=$2
missing=0

while read -r sha; do
  [ -n "$sha" ] || continue
  if ! git show --no-patch --format=%B "$sha" | grep -qE '^Signed-off-by: .+ <[^<>]+@[^<>]+>[[:space:]]*$'; then
    echo "missing Signed-off-by: $sha $(git show --no-patch --format=%s "$sha")"
    missing=1
  fi
done < <(git rev-list --no-merges "$base..$head")

if [ "$missing" -ne 0 ]; then
  echo
  echo "Sign off your commits with 'git commit -s'. See CONTRIBUTING.md and https://github.com/ezclassify/ezclassify/blob/main/DCO.md"
  exit 1
fi

echo "All commits in $base..$head are signed off."
