#!/bin/sh
# Test the release notes parser without a build or GitHub credentials.
set -eu

parser=$(dirname "$0")/release-notes.sh
tmp=$(mktemp -d)
trap 'rm -r -- "$tmp"' EXIT
cat >"$tmp/changelog" <<'EOF'
## [Unreleased]

- Unreleased change.

## [0x217y0] - 2026-11-14

- Similar heading.

## [0.217.0] - 2026-11-14

### Fixed

- Release change.

## [0.216.0] - 2026-11-14

## [0.215.0] - 2026-11-14

### Fixed
EOF
printf '\n### Fixed\n\n- Release change.\n\n' >"$tmp/expected"
sh "$parser" 0.217.0 "$tmp/changelog" >"$tmp/notes"
diff -u "$tmp/expected" "$tmp/notes"

for version in 0.218.0 0.216.0 0.215.0; do
    if sh "$parser" "$version" "$tmp/changelog" >"$tmp/notes" 2>"$tmp/error"; then
        echo "Expected release notes for $version to fail" >&2
        exit 1
    fi
    grep -Fq "[$version]" "$tmp/error"
done

echo "Release notes: 4 cases passed"
