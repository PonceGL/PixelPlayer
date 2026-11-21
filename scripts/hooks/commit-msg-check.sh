#!/usr/bin/env bash
# Checks a commit message: `type(scope): summary` on the first line, no co-author trailers.
# Usage: commit-msg-check.sh <file with the message>   (git passes it to the commit-msg hook)
set -u

message_file="${1:?missing the file with the commit message}"
types='feat|fix|chore|docs|refactor|test|build|ci|perf|style|revert'
max_subject=120

# Comment lines are not part of the message.
body="$(grep -v '^#' "$message_file" || true)"
subject="$(printf '%s\n' "$body" | sed -n '/./{p;q;}')"

fail() {
  printf 'commit-msg: %s\n' "$1" >&2
  printf '  expected:  type(scope): summary      types: %s\n' "${types//|/, }" >&2
  exit 1
}

# Generated messages are left alone: merges, reverts made by git, fixup/squash commits.
case "$subject" in
  Merge\ * | Revert\ \"* | fixup\!\ * | squash\!\ * | amend\!\ *) exit 0 ;;
esac

[ -n "$subject" ] || fail "the message is empty"

if ! printf '%s\n' "$subject" | grep -Eq "^($types)(\([a-z0-9._/-]+\))?!?: [^ ].*"; then
  fail "the first line does not look like 'type(scope): summary': $subject"
fi

if [ "${#subject}" -gt "$max_subject" ]; then
  fail "the first line has ${#subject} characters; the limit is $max_subject"
fi

if printf '%s\n' "$body" | grep -Eiq '^(co-authored-by|claude-session):'; then
  fail "this repository takes no Co-Authored-By or Claude-Session trailers"
fi

exit 0
