#!/usr/bin/env bash
# Tests for scripts/format-check.sh. Needs ktlint. Run: scripts/test-format-check.sh
set -u

here="$(cd "$(dirname "$0")" && pwd)"
repo="$(mktemp -d)"
trap 'rm -rf "$repo"' EXIT
failures=0
count=0

check() { # <name> <expected exit: 0 or nonzero> <actual exit>
  count=$((count + 1))
  if { [ "$2" = 0 ] && [ "$3" -ne 0 ]; } || { [ "$2" != 0 ] && [ "$3" -eq 0 ]; }; then
    printf 'FAIL  %s: expected exit %s, got %s\n' "$1" "$2" "$3"
    failures=$((failures + 1))
  fi
}

git init -q "$repo" || exit 1
cp "$here/../.editorconfig" "$repo/.editorconfig" || exit 1
cd "$repo" || exit 1
git config user.email t@example.com || exit 1
git config user.name t || exit 1
mkdir -p src || exit 1

# The code that predates the rules: trailing spaces, on main.
printf 'package a\n\nfun legacy() = 1   \n' > src/Legacy.kt
printf 'package a\n\nfun keep() = 2\n' > src/Keep.kt
git add -A && git commit -q -m "base" && git branch -M main

git checkout -q -b change
"$here/format-check.sh" main > /dev/null 2>&1
check "nothing changed" 0 $?

printf 'package a\n\nfun clean() = 3\n' > src/Clean.kt
git add -A && git commit -q -m "clean file"
"$here/format-check.sh" main > /dev/null 2>&1
check "a new clean file" 0 $?

printf 'package a\n\nfun dirty() = 4   \n' > src/Dirty.kt
git add -A && git commit -q -m "dirty file"
"$here/format-check.sh" main > /dev/null 2>&1
check "a new file with trailing spaces" 1 $?

git rm -q -f src/Dirty.kt && git commit -q -m "remove it"
"$here/format-check.sh" main > /dev/null 2>&1
check "the dirty file is gone again" 0 $?

printf 'package a\n\nfun keep() = 5\n' > src/Keep.kt
git add -A && git commit -q -m "touch a clean old file"
"$here/format-check.sh" main > /dev/null 2>&1
check "a touched old file that is clean" 0 $?

printf 'package a\n\nfun legacy() = 6\n\n\n\nfun more() = 7\n' > src/Legacy.kt
git add -A && git commit -q -m "touch the dirty old file"
"$here/format-check.sh" main > /dev/null 2>&1
check "a touched old file that is still dirty (blank lines)" 1 $?

# Names git would quote (a space, an accent) must be checked, not skipped.
git checkout -q -b odd main || exit 1
printf 'package a\n\nfun odd() = 8   \n' > "src/Canción rara.kt"
git add -A && git commit -q -m "a file with a space and an accent in its name, dirty"
"$here/format-check.sh" main > /dev/null 2>&1
check "a dirty file whose name has a space and an accent" 1 $?

"$here/format-check.sh" no-such-base > /dev/null 2>&1
check "a base that does not exist" 1 $?

printf '%d cases, %d failed\n' "$count" "$failures"
[ "$failures" -eq 0 ]
