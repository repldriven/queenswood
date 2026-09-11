#!/usr/bin/env bash
# Doc-quality checks for Queenswood.
# Sources every verification command in
#   docs/recipes/practices/writing-docs.md
# Prints PASS/FAIL per check; FAIL includes file:line refs.
#
# Run from the repository root.

cd "$(git rev-parse --show-toplevel 2>/dev/null || pwd)" || exit 1

# Build file lists. Globs assume no spaces in paths (true for this repo).
#
# Scope: prose-shaped docs only — ADRs, recipes, TDDs, PRDs.
# Excludes:
#   docs/slides/  — slidev presentations, not prose
#   docs/plan/    — in-flight implementation plans with
#                   quoted REPL output / debug transcripts
#   node_modules/ — vendored dependency docs we don't author;
#                   without this every check drowns in third-party
#                   readmes and reports FAIL unconditionally
DOCS_MD=( $(find docs -type f -name '*.md' \
              -not -path 'docs/slides/*' \
              -not -path 'docs/plan/*' \
              -not -path '*/node_modules/*' 2>/dev/null) )
TOP_MD=( readme.md CLAUDE.md )
PRD_MD=( $(ls docs/prd/*.md 2>/dev/null) )
ALL_MD=( "${DOCS_MD[@]}" "${TOP_MD[@]}" )

# The writing-docs recipe documents the patterns this script
# checks for, by design — it shows "Bad" / "OK" pairs. Exclude
# it from content-pattern checks (still subject to wrap and
# mermaid checks).
PATTERN_MD=( $(printf '%s\n' "${ALL_MD[@]}" \
                 | grep -v '^docs/recipes/practices/writing-docs\.md$') )

section() {
  printf '\n### %s\n\n' "$1"
}

# Report PASS if no output, otherwise FAIL plus the output as a fenced block.
report() {
  local label="$1"
  local out="$2"
  if [ -z "$out" ]; then
    printf 'PASS — %s\n' "$label"
  else
    printf 'FAIL — %s\n\n```\n%s\n```\n' "$label" "$out"
  fi
}

# 1. Over-80 prose lines. What cannot wrap is not measured: any fenced
# block (mermaid or code, indented under a bullet or not), table rows,
# HTML tags, and a link's target -- the recipe forbids breaking a link,
# so a line is measured with its `](...)` targets removed.
section 'Over-80 prose lines (excluding fences, tables, HTML, link targets)'
out=$(awk '
  FNR == 1            { in_f = 0 }
  /^[ ]*```/          { in_f = !in_f; next }
  in_f                { next }
  /^[ ]*\|/           { next }
  /^[ ]*<[a-zA-Z!\/]/ { next }
  {
    l = $0
    gsub(/\]\([^)]*\)/, "]()", l)
    if (length(l) > 80) print FILENAME":"FNR": "length" chars"
  }
' "${ALL_MD[@]}")
report 'wrap' "$out"

# 2. Semicolons inside mermaid blocks (breaks GitHub render).
section 'Semicolons inside mermaid blocks'
out=$(awk '
  FNR == 1     { in_m = 0 }
  /```mermaid/ { in_m = 1; next }
  /^```$/      { in_m = 0; next }
  in_m && /;/  { print FILENAME":"FNR": "$0 }
' "${ALL_MD[@]}")
report 'mermaid-semicolon' "$out"

# 3. Paren-adjacent links — `]([url]))`.
section 'Paren-adjacent links — `]([url]))`'
out=$(grep -nE '\]\([^)]+\)\)' "${PATTERN_MD[@]}" 2>/dev/null)
report 'paren-adjacent-link' "$out"

# 4. Cross-level relative links — `../../../`. Two levels is what a
# recipe in docs/recipes/<chapter>/ costs to reach an ADR; three means
# the doc is reaching for a file outside docs/, which is a repo-root
# link. PATTERN_MD rather than DOCS_MD: writing-docs shows the climb as
# the Bad half of that pair.
section 'Cross-level relative links — `../../../`'
out=$(grep -nE '\]\(\.\./\.\./\.\./' "${PATTERN_MD[@]}" 2>/dev/null)
report 'cross-level-link' "$out"

# 4b. Repo-root links — [text](/path). GitHub resolves a leading `/`
# against the repository root, which is how a doc reaches a file outside
# docs/. Two ways to get it wrong: a target that does not exist, and a
# markdown target (between docs the link is relative, which also works
# in an editor).
section 'Repo-root links that do not resolve'
out=$(grep -noE '\]\(/[^)]+\)' "${PATTERN_MD[@]}" 2>/dev/null \
        | sed 's/](\///; s/)$//' \
        | while IFS=: read -r f n target; do
            [ -e "$target" ] || echo "$f:$n: $target"
          done)
report 'repo-root-link-missing' "$out"

section 'Repo-root links to markdown'
out=$(grep -nE '\]\(/[^)]+\.md\)' "${PATTERN_MD[@]}" 2>/dev/null)
report 'repo-root-link-markdown' "$out"

# 5. Inline-code as the entire link text — [`...`](...).
section 'Inline-code as entire link text'
out=$(grep -nE '\[`[^`]+`\]\(' "${PATTERN_MD[@]}" 2>/dev/null)
report 'code-link-text' "$out"

# 6. Maturity overclaim.
section 'Maturity overclaim'
out=$(grep -niE 'battle.?tested|production.?proven|years of hardening|battle.?hardened|industry.?proven' "${PATTERN_MD[@]}" 2>/dev/null)
report 'maturity-overclaim' "$out"

# 7. Competitor names.
section 'Competitor names'
out=$(grep -nwE 'Revolut|Griffin|Kroo|Monzo|Starling' "${PATTERN_MD[@]}" 2>/dev/null)
report 'competitor-names' "$out"

# 8. PRD-only: engineering vocabulary.
section 'PRD-specific: engineering vocabulary'
if [ ${#PRD_MD[@]} -gt 0 ]; then
  out=$(grep -nwE 'synchronous|asynchronous|reactive|primitive|watcher|relay|handler|idempotent|deterministic|choreography|saga|orchestrator|changelog|brick' "${PRD_MD[@]}" 2>/dev/null)
  report 'prd-engineering-vocab' "$out"
else
  echo 'PASS — prd-engineering-vocab (no PRDs found)'
fi

# 9. PRD-only: backticked operation names (kebab-case verb-X).
section 'PRD-specific: operation names'
if [ ${#PRD_MD[@]} -gt 0 ]; then
  out=$(grep -nE '`(create|submit|open|close|update|new|publish|discard|accrue|capitalize|capitalise|register|approve|reject|delete|seed|initiate)-[a-z][a-z-]*`' "${PRD_MD[@]}" 2>/dev/null)
  report 'prd-operation-names' "$out"
else
  echo 'PASS — prd-operation-names (no PRDs found)'
fi

# 10. Brittle counts of artefacts and relative-time framings.
section 'Brittle counts, datestamps, relative-time framings'
out=$(
  {
    grep -niE '\b(twelve|thirteen|fourteen|fifteen|sixteen|seventeen|eighteen|nineteen|twenty|twenty-one|twenty-two|twenty-three|twenty-four)\b.{0,30}\b(ADRs?|TDDs?|PRDs?|recipes|services|projects|bases|bricks|components|memories|topics|schemas|records|domains|capabilities)\b' "${PATTERN_MD[@]}"
    grep -nwiE 'recently|lately|fortnight' "${PATTERN_MD[@]}"
    grep -nE '\b(January|February|March|April|May|June|July|August|September|October|November|December)\s+20[0-9][0-9]\b' "${PATTERN_MD[@]}"
  } 2>/dev/null | sort -u
)
report 'brittle-temporal' "$out"
