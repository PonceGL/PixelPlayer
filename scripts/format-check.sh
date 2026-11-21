#!/usr/bin/env bash
# Checks the format of the Kotlin files that changed against a base (default: origin/main) with ktlint, using the
# rules of .editorconfig. Only the files a change touches are checked, so the code that predates the rules is not a
# reason to fail; touch one of those files and it has to be clean.
# Usage: scripts/format-check.sh [base-ref]      To fix what it finds: ktlint -F <file>
set -u

base="${1:-origin/main}"

if ! command -v ktlint > /dev/null 2>&1; then
  echo "format-check: ktlint is not installed (brew install ktlint, or scripts/ci/install-ktlint.sh)" >&2
  exit 2
fi

root="$(git rev-parse --show-toplevel)" || exit 2
cd "$root" || exit 2

if ! git rev-parse --verify --quiet "$base" > /dev/null; then
  echo "format-check: the base '$base' does not exist here (git fetch origin main?)" >&2
  exit 2
fi

# Added, copied, modified and renamed files; a deleted file has nothing to check. Three dots: from the common ancestor.
# Null-terminated, so that a name with a space or an accent (which git would otherwise quote) is not skipped.
files=()
while IFS= read -r -d '' file; do
  [ -f "$file" ] && files+=("$file")
done < <(git diff -z --name-only --diff-filter=ACMR "$base"...HEAD -- '*.kt' '*.kts')

if [ "${#files[@]}" -eq 0 ]; then
  echo "format-check: no Kotlin files changed against $base"
  exit 0
fi

echo "format-check: ${#files[@]} Kotlin file(s) changed against $base"
ktlint --relative "${files[@]}"
