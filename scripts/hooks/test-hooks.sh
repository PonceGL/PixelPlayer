#!/usr/bin/env bash
# Tests for the scripts of the git hooks. Run: scripts/hooks/test-hooks.sh
set -u

here="$(cd "$(dirname "$0")" && pwd)"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
failures=0
count=0

# expect <accepted|rejected> <name> <message>
message_case() {
  local expected="$1" name="$2" text="$3" file="$work/msg"
  printf '%b' "$text" > "$file"
  count=$((count + 1))
  if "$here/commit-msg-check.sh" "$file" > /dev/null 2>&1; then actual=accepted; else actual=rejected; fi
  if [ "$actual" != "$expected" ]; then
    printf 'FAIL  %s: expected %s, got %s\n' "$name" "$expected" "$actual"
    failures=$((failures + 1))
  fi
}

message_case accepted "plain feat" "feat: add the thing\n"
message_case accepted "scope" "fix(sonos): refuse shuffle while a speaker plays\n"
message_case accepted "scope with path-like characters" "chore(ci/release): pin the runner\n"
message_case accepted "breaking mark" "feat(api)!: drop the old endpoint\n"
message_case accepted "body after a blank line" "test(utils): rebuild a test\n\nIt never ran because its tests were nested.\n"
message_case accepted "comment lines are ignored" "docs: explain the hooks\n# Please enter the commit message\n# Lines starting with '#' are ignored\n"
message_case accepted "merge commit" "Merge pull request #46 from PonceGL/feature/sonos\n"
message_case accepted "revert made by git" "Revert \"feat: add the thing\"\n"
message_case accepted "fixup" "fixup! feat: add the thing\n"
message_case accepted "a first line of 120 characters" "feat: $(printf 'a%.0s' $(seq 1 114))\n"
message_case rejected "no type" "add the thing\n"
message_case rejected "unknown type" "wip: half way\n"
message_case rejected "no space after the colon" "feat:add the thing\n"
message_case rejected "empty summary" "feat: \n"
message_case rejected "uppercase scope" "feat(Sonos): add the thing\n"
message_case rejected "empty message" "\n"
message_case rejected "only comments" "# nothing here\n"
message_case rejected "a first line of 121 characters" "feat: $(printf 'a%.0s' $(seq 1 115))\n"
message_case rejected "co-author trailer" "feat: add the thing\n\nCo-Authored-By: Someone <someone@example.com>\n"
message_case rejected "co-author trailer in lowercase" "feat: add the thing\n\nco-authored-by: Someone <someone@example.com>\n"
message_case rejected "claude session trailer" "feat: add the thing\n\nClaude-Session: https://claude.ai/code/session_1\n"
message_case accepted "co-author mentioned in the text, not a trailer" "docs: say why there is no Co-Authored-By trailer\n"

# expect_size <accepted|rejected> <name> <bytes> [allowlist the file]
size_case() {
  local expected="$1" name="$2" bytes="$3" allow="${4:-}" file="$work/blob.bin"
  head -c "$bytes" /dev/zero > "$file"
  count=$((count + 1))
  local list="$here/large-files-allowlist.txt" saved
  saved="$(cat "$list")"
  if [ -n "$allow" ]; then printf '%s\n' "$file" >> "$list"; fi
  if "$here/large-files-check.sh" "$file" > /dev/null 2>&1; then actual=accepted; else actual=rejected; fi
  printf '%s' "$saved" > "$list"
  if [ "$actual" != "$expected" ]; then
    printf 'FAIL  %s: expected %s, got %s\n' "$name" "$expected" "$actual"
    failures=$((failures + 1))
  fi
}

size_case accepted "a small file" 1000
size_case accepted "exactly 1 MB" 1048576
size_case rejected "1 byte over 1 MB" 1048577
size_case accepted "a big file that is in the allowlist" 2000000 allow

count=$((count + 1))
if "$here/large-files-check.sh" "$work/does-not-exist" > /dev/null 2>&1; then :; else
  printf 'FAIL  a path that is gone (a deleted file) must not fail\n'
  failures=$((failures + 1))
fi

printf '%d cases, %d failed\n' "$count" "$failures"
[ "$failures" -eq 0 ]
