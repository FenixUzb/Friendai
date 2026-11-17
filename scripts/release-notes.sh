#!/bin/sh
# Print the notes for one version. A heading alone is not a release note.
set -eu

version=$1
changelog=$2
awk -v heading="## [$version]" '
    /^##[[:blank:]]/ {
        if (capture) exit
        capture = ($0 == heading || index($0, heading " - ") == 1)
        next
    }
    capture {
        print
        if ($0 ~ /[^[:space:]]/ && $0 !~ /^[[:space:]]*#+[[:blank:]]/) content = 1
    }
    END { if (!content) exit 1 }
' "$changelog" || {
    printf 'No release notes for [%s] in %s\n' "$version" "$changelog" >&2
    exit 1
}
