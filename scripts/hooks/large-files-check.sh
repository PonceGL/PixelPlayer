#!/usr/bin/env bash
# Refuses files over 1 MB in a commit unless they are listed in scripts/hooks/large-files-allowlist.txt.
# Usage: large-files-check.sh <file>...   (lefthook passes the staged files)
set -u

limit=$((1024 * 1024))
# The list can be pointed elsewhere (the tests do, so they never touch the tracked one).
allowlist="${LARGE_FILES_ALLOWLIST:-$(dirname "$0")/large-files-allowlist.txt}"
status=0

for file in "$@"; do
  [ -f "$file" ] || continue
  size="$(wc -c < "$file" | tr -d ' ')"
  [ "$size" -gt "$limit" ] || continue
  if [ -f "$allowlist" ] && grep -Fxq -- "$file" "$allowlist"; then
    continue
  fi
  printf 'large-files: %s is %s bytes (limit %s). Use a smaller file, or list it in %s\n' "$file" "$size" "$limit" "$allowlist" >&2
  status=1
done

exit "$status"
