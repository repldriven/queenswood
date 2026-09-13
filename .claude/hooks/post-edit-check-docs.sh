#!/usr/bin/env bash
# PostToolUse hook for Edit/Write/MultiEdit on markdown files
# in the doc-quality scope. Runs the same check-docs script
# the installed skill uses and surfaces findings to the agent.
#
# Stdin: JSON with the tool-use event. We inspect
# `tool_input.file_path` (Edit/Write) or `tool_input.edits[*].file_path`
# (MultiEdit) and exit silently unless one of the touched
# files is in scope.
#
# Stdout: human-readable findings, captured by the harness
# and surfaced to the agent. Empty stdout means no surface.
#
# Exit code: always 0 (advisory; never block).

set -u

cd "$(git rev-parse --show-toplevel 2>/dev/null || pwd)" || exit 0

# Read the event JSON. If jq isn't available or the JSON is
# malformed, exit silently rather than spamming the agent.
input="$(cat)"
if ! command -v jq >/dev/null 2>&1; then
  exit 0
fi

# Collect every path the tool touched (Edit/Write give one;
# MultiEdit gives many).
paths="$(printf '%s' "$input" | jq -r '
  [
    .tool_input.file_path // empty,
    (.tool_input.edits // [] | .[] | .file_path // empty)
  ] | .[] | select(length > 0)
' 2>/dev/null)"

[ -z "$paths" ] && exit 0

# Filter to in-scope markdown: docs/**/*.md (excluding
# docs/slides/ and docs/plan/), plus readme.md and CLAUDE.md.
in_scope() {
  local p="$1"
  # Strip absolute prefix if present.
  p="${p#$(pwd)/}"
  case "$p" in
    docs/slides/*) return 1 ;;
    docs/plan/*)   return 1 ;;
    docs/*.md|docs/*/*.md|docs/*/*/*.md) return 0 ;;
    readme.md|CLAUDE.md) return 0 ;;
    *) return 1 ;;
  esac
}

touched_in_scope=0
while IFS= read -r p; do
  if in_scope "$p"; then
    touched_in_scope=1
    break
  fi
done <<< "$paths"

[ "$touched_in_scope" -eq 0 ] && exit 0

# Run the check-docs script, which mono's docs plugin installs as
# a skill; a tree that has not installed the plugins has no script
# and stays silent. Capture and report only when at least one
# section FAILed — keep silent on a clean run so the hook doesn't
# spam after every edit.
script=.claude/skills/tessl__check-docs/checks.sh
[ -f "$script" ] || exit 0
out="$(bash "$script" 2>&1)"

if printf '%s' "$out" | grep -q '^FAIL — '; then
  echo "doc-quality findings (after $(printf '%s' "$paths" | tr '\n' ' '))"
  echo
  printf '%s\n' "$out"
fi

exit 0
