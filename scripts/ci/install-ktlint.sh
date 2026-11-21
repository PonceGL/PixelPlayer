#!/usr/bin/env bash
# Installs the pinned ktlint where a CI runner can use it. The version and checksum are pinned on purpose: update
# both together (the checksum is of the release binary, which GitHub does not publish a sum for).
# Usage: scripts/ci/install-ktlint.sh [directory]     (default: $HOME/.local/bin)
set -eu

version="1.8.0"
sha256="a3fd620207d5c40da6ca789b95e7f823c54e854b7fade7f613e91096a3706d75"
dir="${1:-$HOME/.local/bin}"

mkdir -p "$dir"
curl -sSfL -o "$dir/ktlint" "https://github.com/pinterest/ktlint/releases/download/$version/ktlint"
# macOS has shasum, a minimal Linux may have only sha256sum.
if command -v sha256sum > /dev/null 2>&1; then
  echo "$sha256  $dir/ktlint" | sha256sum -c -
else
  echo "$sha256  $dir/ktlint" | shasum -a 256 -c -
fi
chmod +x "$dir/ktlint"
"$dir/ktlint" --version
