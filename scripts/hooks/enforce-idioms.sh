#!/usr/bin/env bash
# Deterministic guardrail linter for Queenswood Clojure code.
# Enforces the "Critical guardrails" from CLAUDE.md that a deterministic
# grep can decide. Run by the pre-commit hook in --staged mode; also
# runnable standalone for a whole-branch or explicit-path sweep.
#
# The split against .config/semgrep/semgrep.yml: a guardrail decidable
# from one file's tokens plus its path lives there, because semgrep gives
# it a per-site `nosemgrep` opt-out. What lands here needs knowledge no single file
# carries — a count across the tree, a declaration matched to a reference
# in another file, or the name of the brick a file sits in.
#
# Exit status: non-zero if any BLOCKING check fails, so it can gate a
# commit. Two are advisory (WARN, never blocks): `comment-block-bloat`,
# because a long comment block is sometimes legitimately load-bearing,
# and `interface-imports-foreign-brick`, which still has debt to clear.
#
#   bash enforce-idioms.sh           # branch scope (default)
#   bash enforce-idioms.sh --staged  # only staged changes (pre-commit)
#   bash enforce-idioms.sh --all     # every tracked Clojure file
#   bash enforce-idioms.sh <path>... # explicit paths

cd "$(git rev-parse --show-toplevel 2>/dev/null || pwd)" || exit 1

# --- Argument parsing ----------------------------------------------------

scope=branch
EXPLICIT=()
for arg in "$@"; do
  case "$arg" in
    --staged) scope=staged ;;
    --all)    scope=all ;;
    --branch) scope=branch ;;
    --*)      echo "Unknown flag: $arg" >&2; exit 2 ;;
    *)        EXPLICIT+=("$arg") ;;
  esac
done
if [ ${#EXPLICIT[@]} -gt 0 ]; then
  scope=explicit
fi

# --- Collect candidate files --------------------------------------------

case "$scope" in
  branch)
    base=$(git merge-base HEAD origin/main 2>/dev/null \
             || git merge-base HEAD main 2>/dev/null \
             || git rev-parse origin/main 2>/dev/null \
             || git rev-parse main 2>/dev/null)
    RAW=( $({
      [ -n "$base" ] && git diff --name-only "$base"..HEAD 2>/dev/null
      git diff --name-only 2>/dev/null
      git ls-files --others --exclude-standard 2>/dev/null
    } | sort -u) )
    ;;
  staged)
    RAW=( $(git diff --name-only --cached 2>/dev/null | sort -u) )
    ;;
  all)
    RAW=( $(git ls-files 2>/dev/null | sort -u) )
    ;;
  explicit)
    RAW=( "${EXPLICIT[@]}" )
    ;;
esac

# Filter to Clojure / deps.edn and keep only files that still exist.
ALL_FILES=()
for f in "${RAW[@]}"; do
  # Skip eval fixtures — planted violations, test inputs, not code.
  case "$f" in */evals/*|evals/*) continue ;; esac
  case "$f" in
    *.clj|*.cljc|*deps.edn) [ -f "$f" ] && ALL_FILES+=("$f") ;;
  esac
done

# A config-only commit still has to face check 5 — the include check is
# whole-tree and is exactly what a YAML rename breaks.
if [ ${#ALL_FILES[@]} -eq 0 ]; then
  printf 'Scope: %s — no Clojure files; running config checks only.\n' "$scope"
else
  printf 'Scope: %s — %d file(s)\n' "$scope" "${#ALL_FILES[@]}"
fi

# Category subsets.
SRC_CLJ=( $(printf '%s\n' "${ALL_FILES[@]}" | grep -E '\.(clj|cljc)$') )

# --- Helpers -------------------------------------------------------------

FAILED=0

section() { printf '\n### %s\n\n' "$1"; }

report() {
  local label="$1"
  local out="$2"
  local advisory="$3"   # non-empty → report as WARN, never gate the commit
  if [ -z "$out" ]; then
    printf 'PASS — %s\n' "$label"
  elif [ -n "$advisory" ]; then
    printf 'WARN — %s (advisory)\n\n```\n%s\n```\n' "$label" "$out"
  else
    printf 'FAIL — %s\n\n```\n%s\n```\n' "$label" "$out"
    FAILED=1
  fi
}

# --- Checks --------------------------------------------------------------

# 1. Cross-unit internal imports.
# Rules:
#   - intra-unit (target == own) is always fine
#   - target is a component: only `.interface` and `.system` are public
#   - target is a base: only `.interface`, `.system` or `.api` are public,
#     and only when the importer is itself a base (component → base is the
#     wrong direction). This is the multi-base aggregator pattern — the
#     aggregator wires several composed bases into one process. A composed
#     base carries an `interface.clj` and that is the form to reach it by;
#     `.api` remains for a base that has no interface (bank-api).
#   - target is neither a component nor a base: ignore (generated namespaces
#     like com.repldriven.queenswood.schemas.* live under a brick's gen/
#     tree, and `schemas` is not the `schema` brick's own name)
#
# Both prefixes are matched, but only a name that is also a local brick or
# base directory is judged — a mono namespace resolves to neither, so
# reaching into mono's internals is not what this check is for.
section 'Cross-unit internal imports (components and bases)'
out=""
if [ ${#SRC_CLJ[@]} -gt 0 ]; then
  bricks_us=$(ls components 2>/dev/null | paste -sd, -)
  bases_us=$(ls bases 2>/dev/null | paste -sd, -)
  out=$(awk -v bricks="$bricks_us" -v bases="$bases_us" '
    BEGIN {
      n = split(bricks, a, ",")
      for (i = 1; i <= n; i++) if (a[i] != "") is_brick[a[i]] = 1
      n = split(bases, a, ",")
      for (i = 1; i <= n; i++) if (a[i] != "") is_base[a[i]] = 1
    }
    function unit_of(p, kind,    parts, n, b) {
      n = split(p, parts, "/")
      if (n < 4 || parts[3] != "src") return ""
      if (parts[1] != "components" && parts[1] != "bases") return ""
      b = parts[2]
      kind["k"] = (parts[1] == "components") ? "component" : "base"
      return b
    }
    FNR == 1 {
      delete kindbuf
      own = unit_of(FILENAME, kindbuf)
      own_kind = kindbuf["k"]
    }
    own == "" { next }
    {
      line = $0
      while (match(line, /com\.repldriven\.(mono|queenswood)\.[a-z0-9_-]+\.[a-z0-9_-]+/)) {
        s = substr(line, RSTART, RLENGTH)
        line = substr(line, RSTART + RLENGTH)
        split(s, p, ".")
        target = p[4]
        sub_ns = p[5]
        if (target == own) continue
        bad = 0
        if (target in is_brick) {
          if (sub_ns != "interface" && sub_ns != "system") bad = 1
        } else if (target in is_base) {
          if (!(own_kind == "base" &&
                (sub_ns == "interface" || sub_ns == "system" ||
                 sub_ns == "api"))) bad = 1
        }
        if (bad) print FILENAME ":" FNR ": " s
      }
    }
  ' "${SRC_CLJ[@]}" 2>/dev/null)
fi
report 'cross-unit-internal' "$out"

# 2. interface.clj requires only its own component's local namespaces.
# Stricter than cross-unit-internal above: that check allows any file to
# require a foreign brick's `.interface`, but `interface.clj` itself must
# delegate to its own core/domain/store/etc. and never reach into another
# brick at all — not even via that brick's `.interface`, and not even a
# library-wrapper brick like `error`/`utility`. Composition across bricks
# belongs one level down, in core.clj. Advisory: four interfaces
# (balance-query, cash-account, party twice) still reach across and
# haven't migrated — promote to blocking once they have.
section "interface.clj requires only its own component's namespaces"
out=""
if [ ${#SRC_CLJ[@]} -gt 0 ]; then
  bricks_us=$(ls components 2>/dev/null | paste -sd, -)
  bases_us=$(ls bases 2>/dev/null | paste -sd, -)
  out=$(awk -v bricks="$bricks_us" -v bases="$bases_us" '
    BEGIN {
      n = split(bricks, a, ",")
      for (i = 1; i <= n; i++) if (a[i] != "") is_brick[a[i]] = 1
      n = split(bases, a, ",")
      for (i = 1; i <= n; i++) if (a[i] != "") is_base[a[i]] = 1
    }
    function unit_of(p,    parts, n, b) {
      n = split(p, parts, "/")
      if (n < 4 || parts[3] != "src") return ""
      if (parts[1] != "components" && parts[1] != "bases") return ""
      b = parts[2]
      return b
    }
    FNR == 1 {
      own = unit_of(FILENAME)
      is_iface = (FILENAME ~ /\/interface\.clj$/)
    }
    own == "" || !is_iface { next }
    {
      line = $0
      while (match(line, /com\.repldriven\.(mono|queenswood)\.[a-z0-9_-]+\.[a-z0-9_-]+/)) {
        s = substr(line, RSTART, RLENGTH)
        line = substr(line, RSTART + RLENGTH)
        split(s, p, ".")
        target = p[4]
        if (target == own) continue
        if ((target in is_brick) || (target in is_base)) {
          print FILENAME ":" FNR ": " s
        }
      }
    }
  ' "${SRC_CLJ[@]}" 2>/dev/null)
fi
report 'interface-imports-foreign-brick' "$out" advisory

# 3. Comment-block bloat — runs of 5+ consecutive `;`-comment lines.
# Advisory: a long block can be a legitimate load-bearing why-block, so
# this WARNs rather than blocking the commit.
section 'Comment-block bloat (>= 5 consecutive `;` lines)'
out=""
if [ ${#SRC_CLJ[@]} -gt 0 ]; then
  out=$(awk '
    function flush() {
      if (run >= 5) {
        print prevfile ":" start ": " run " consecutive comment lines"
      }
      run = 0
    }
    FNR == 1 { flush() }
    { prevfile = FILENAME }
    /^[[:space:]]*;/ {
      if (run == 0) start = FNR
      run++
      next
    }
    { flush() }
    END { flush() }
  ' "${SRC_CLJ[@]}" 2>/dev/null)
fi
report 'comment-block-bloat' "$out" advisory

# 4. api reads are query-only (ADR-0017).
# A CQRS/design invariant riding along here until the `design` plugin owns
# its enforcement. A domain brick with a `components/<brick>-query` sibling
# is split into a read side (`-query`) and a write side (the plain name).
# `api` request code may require the `-query` interface but must not
# require the write brick's interface — writes go over the bus as commands.
#
# A `-query` sibling means read/write separation, which is not the same
# thing as commands: a split can outlive them. ADR-0018 decides which
# writes earn a command, so a brick whose writes are synchronous is named
# below and the API calls it directly. Keeping that an explicit list
# rather than relaxing the check means a new direct call is a deliberate
# edit here, not a silent one over there.
#
# The `system.clj` registration bundle is exempt: it bare-requires
# interfaces to register component-kinds (not to call them), and stays
# until the write brick leaves the API project (`poly check` Tier-2).
# The base path and namespace prefix are asserted below: this check is a
# pair of greps over names that a rename silently empties, and an empty
# candidate set reports PASS rather than failing.
section 'api reads are query-only'
out=""
API_BASE=bases/api/src
API_NS=com.repldriven.queenswood
if [ ! -d "$API_BASE" ]; then
  echo "  enforce-idioms: $API_BASE not found — check 4 cannot run" >&2
  FAILED=1
elif ! grep -rq "$API_NS\." "$API_BASE" 2>/dev/null; then
  echo "  enforce-idioms: no $API_NS.* under $API_BASE — check 4 cannot run" >&2
  FAILED=1
fi
# Write bricks whose writes earn no command (ADR-0018), so the API
# calls their interface directly.
API_SYNCHRONOUS_WRITE_BRICKS=( cash-account-product )
API_SRC=( $(printf '%s\n' "${SRC_CLJ[@]}" \
             | grep -E "^$API_BASE/" | grep -v '/system\.clj$') )
if [ ${#API_SRC[@]} -gt 0 ]; then
  for qdir in components/*-query; do
    [ -d "$qdir" ] || continue
    write_brick=$(basename "${qdir%-query}")
    skip=""
    for allowed in "${API_SYNCHRONOUS_WRITE_BRICKS[@]}"; do
      [ "$write_brick" = "$allowed" ] && skip=1
    done
    [ -n "$skip" ] && continue
    hits=$(grep -nE "$API_NS\.${write_brick}\.interface\b" \
             "${API_SRC[@]}" 2>/dev/null)
    [ -n "$hits" ] && out="${out}${hits}"$'\n'
  done
  out=$(printf '%s' "$out" | grep -v '^$' || true)
fi
report 'api-reads-are-query-only' "$out"

# 5. Config include targets resolve.
# `!include` and `-c classpath:` name a path on the classpath, which is every
# `resources` and `test-resources` directory merged. Renaming a file leaves
# the references pointing at nothing, and nothing catches it: no test loads a
# project's production application.yml or the dev-loop entry point, so the
# first symptom is a service that will not start. Three such breakages shipped
# in #275 and #276 alone.
#
# Whole-tree regardless of scope: the breakage is caused by the rename, so the
# file that now dangles is usually not the one being committed.
section 'Config !include and classpath: targets resolve'
out=""
ROOTS=( $(find components bases projects -type d \
            \( -name target -o -name node_modules -o -name .cpcache \) -prune -o \
            -type d \( -name resources -o -name test-resources \) -print 2>/dev/null) )

resolves() {  # $1 = referenced path, $2 = directory of the referencing file
  [ -e "$2/$1" ] && return 0
  local r
  for r in "${ROOTS[@]}"; do [ -e "$r/$1" ] && return 0; done
  return 1
}

while IFS= read -r hit; do
  [ -z "$hit" ] && continue
  f=${hit%%:*}
  p=$(printf '%s\n' "${hit#*:}" \
        | sed -n 's/.*!include[[:space:]]\{1,\}\([A-Za-z0-9._/-]\{1,\}\).*/\1/p')
  [ -z "$p" ] && continue
  resolves "$p" "$(dirname "$f")" || out="${out}${f}: !include ${p}"$'\n'
done < <(grep -rn --include='*.yml' --include='*.yaml' \
           --exclude-dir=target --exclude-dir=node_modules --exclude-dir=.cpcache \
           '!include' components bases projects 2>/dev/null || true)

# The same path shape appears as a `-c classpath:` argument to a -main.
while IFS= read -r hit; do
  [ -z "$hit" ] && continue
  f=${hit%%:*}
  p=$(printf '%s\n' "${hit#*:}" \
        | sed -n 's/.*classpath:\([A-Za-z0-9._/-]\{1,\}\).*/\1/p')
  [ -z "$p" ] && continue
  resolves "$p" "." || out="${out}${f}: classpath:${p}"$'\n'
done < <(grep -rn 'classpath:' justfiles .envrc scripts 2>/dev/null || true)

out=$(printf '%s' "$out" | grep -v '^$' || true)
report 'config-includes-resolve' "$out"

# 6. Exactly one logback.xml and one logback-test.xml in the workspace.
# Both are looked up by bare name on a merged classpath, so N copies means
# which one wins is classpath-order dependent. Identical copies hide that;
# the moment one drifts you get a config heisenbug with no error to grep for.
# The workspace ran 14 logback.xml and 14 logback-test.xml that had already
# split into three variants, one of them a stale namespace prefix that
# silenced Queenswood's own logging in every deployed service.
#
# logback's own "Resource [logback.xml] occurs multiple times" warning cannot
# be relied on here — the configs install a NopStatusListener, which suppresses
# exactly that message.
#
# Whole-tree regardless of scope: a second copy added anywhere breaks the
# single-source guarantee for every project, not just the one being committed.
section 'One logback.xml and one logback-test.xml'
out=""
for name in logback.xml logback-test.xml; do
  found=$(find components bases projects development \
            \( -name target -o -name node_modules -o -name .cpcache \) -prune -o \
            -type f -name "$name" -print 2>/dev/null | sort)
  count=$(printf '%s' "$found" | grep -c . || true)
  if [ "$count" -ne 1 ]; then
    out="${out}${name}: expected 1, found ${count}"$'\n'
    [ "$count" -gt 0 ] && out="${out}$(printf '%s\n' "$found" | sed 's/^/  /')"$'\n'
  fi
done
out=$(printf '%s' "$out" | grep -v '^$' || true)
report 'single-logback-config' "$out"

# 7. Every declared command/dispatcher is referenced in a dispatchers map.
# A dispatcher declared but never wired starts cleanly and is unreachable:
# the handler looks up [:dispatchers :X], gets nil, and `swap!` on a nil
# atom throws an NPE — on a live request, not at boot. api-service declared
# a `companies` dispatcher for four releases without wiring it, so every
# GET /v1/companies/:number 500'd while the service reported healthy.
#
# Whole-tree regardless of scope: the declaration and the reference live in
# the same file, but a commit touching either one can break the pair.
section 'Declared command/dispatchers are wired into a dispatchers map'
out=""
for f in $(find projects bases components -type f \
             \( -name 'application.yml' -o -name 'application-test.yml' \) \
             2>/dev/null | sort); do
  declared=$(awk '
    /^  [a-z0-9-]+:/ { grp = $1; sub(/:$/, "", grp) }
    /component-kind: command\/dispatcher/ { if (grp != "") print grp }
  ' "$f" | sort -u)
  [ -z "$declared" ] && continue
  wired=$(grep -oE '!system/ref +[a-z0-9-]+(-dispatcher)?\.dispatcher' "$f" \
            | awk '{print $2}' | sed 's/\.dispatcher$//; s/-dispatcher$//' | sort -u)
  for d in $declared; do
    printf '%s\n' "$wired" | grep -qx "$d" \
      || out="${out}${f}: '${d}' dispatcher declared but not in any dispatchers map"$'\n'
  done
done
out=$(printf '%s' "$out" | grep -v '^$' || true)
report 'dispatcher-declared-not-wired' "$out"

# 8. A base owns no store.
# A store is durable state; a base is an entry point chosen by a project.
# `component -> base` is already blocked by check 1, so a store that lived
# in a base would be permanently unreachable by any component -- a one-way
# door, not just an odd shape. It would also leave the guarded set
# silently: `check-processors` iterates `components/*`, and semgrep's
# `fdb-outside-store` is pathed to `/components/*/src/**`, so neither
# would see it. A base still bare-requires `fdb.interface` from its
# `system.clj` to register FDB component-kinds -- that is registration,
# not access, and is the only form allowed.
#
# Whole-tree regardless of scope: a store arrives as a new file, which a
# staged-only diff of edited files would not necessarily surface.
section 'Bases own no store'
out=""
found=$(find bases -type f -name 'store.clj' -not -path '*/test/*' 2>/dev/null | sort)
if [ -n "$found" ]; then
  out="$(printf '%s\n' "$found" | sed 's/$/: store.clj in a base/')"$'\n'
fi
base_fdb=$(grep -rn 'com\.repldriven\.queenswood\.fdb\.interface' \
             --include='*.clj' --include='*.cljc' bases/*/src 2>/dev/null \
           | grep -v '/system\.clj:' || true)
[ -n "$base_fdb" ] && out="${out}$(printf '%s\n' "$base_fdb" \
  | sed 's/$/  <- fdb outside a base system.clj/')"$'\n'
# `[^]]|$` so a require wrapped onto the next line is caught too: a bare
# one always closes with `]` on the same line.
base_fdb_alias=$(grep -rnE 'com\.repldriven\.queenswood\.fdb\.interface([^]]|$)' \
                   --include='*.clj' --include='*.cljc' bases/*/src 2>/dev/null || true)
[ -n "$base_fdb_alias" ] && out="${out}$(printf '%s\n' "$base_fdb_alias" \
  | sed 's/$/  <- fdb require in a base must be bare/')"$'\n'
out=$(printf '%s' "$out" | grep -v '^$' || true)
report 'store-in-a-base' "$out"

# 9. A brick's tests require only what the brick requires.
# A brick's test tree reaches another component only through its
# `.interface` (or `.system`, for registration), and only a component the
# brick's own `src` already requires. A test that drives a further write
# brick to build its fixtures -- a party, a product version, a policy on
# record -- is a scenario, and belongs in `test-scenarios` or
# `test-api-scenarios`. Read-side `*-query` bricks, `test-*` bricks and
# the plumbing (`fdb`, `testcontainers`, `schema`, `changelog-relay`) are
# always in scope. A `system.clj` in a test tree is the sanctioned home
# for bare registration requires and is skipped. An exception carries
# `;; enforce-idioms: brick-test-scope -- <reason>` on the line above the
# require.
#
# Why here rather than `poly check`: polylith validates src requires only,
# so a test namespace needing a component its project lacks loads fine in
# `project:dev` (which carries everything) and fails only when that
# project's own tests run -- in CI, after the push.
section 'Brick tests require only what the brick requires'
out=""
TEST_CLJ=( $(printf '%s\n' "${SRC_CLJ[@]}" \
             | grep -E '^(components|bases)/[^/]+/test/' \
             | grep -v '/system\.clj$' || true) )
if [ ${#TEST_CLJ[@]} -gt 0 ]; then
  bricks_us=$(ls components 2>/dev/null | paste -sd, -)
  # `unit:target` for every component each unit's own src requires.
  src_deps=""
  for u in $(printf '%s\n' "${TEST_CLJ[@]}" | cut -d/ -f1,2 | sort -u); do
    for t in $(grep -rhoE 'com\.repldriven\.queenswood\.[a-z0-9-]+\.' "$u/src" 2>/dev/null \
                 | sed -E 's/^com\.repldriven\.queenswood\.([a-z0-9-]+)\.$/\1/' \
                 | sort -u); do
      src_deps="${src_deps}${u##*/}:${t},"
    done
  done
  out=$(awk -v bricks="$bricks_us" -v deps="$src_deps" '
    BEGIN {
      n = split(bricks, a, ",")
      for (i = 1; i <= n; i++) if (a[i] != "") is_brick[a[i]] = 1
      n = split(deps, a, ",")
      for (i = 1; i <= n; i++) if (a[i] != "") in_src[a[i]] = 1
      plumbing["fdb"] = 1
      plumbing["testcontainers"] = 1
      plumbing["schema"] = 1
      plumbing["changelog-relay"] = 1
    }
    FNR == 1 { split(FILENAME, parts, "/"); own = parts[2]; prev = "" }
    {
      line = $0
      marked = (prev ~ /enforce-idioms: brick-test-scope/)
      while (match(line, /com\.repldriven\.queenswood\.[a-z0-9-]+\.[a-z0-9-]+/)) {
        s = substr(line, RSTART, RLENGTH)
        line = substr(line, RSTART + RLENGTH)
        split(s, p, ".")
        target = p[4]
        sub_ns = p[5]
        if (target == own || !(target in is_brick) || marked) continue
        if (sub_ns != "interface" && sub_ns != "system") {
          print FILENAME ":" FNR ": " s "  <- another brick internals; reach it via .interface"
          continue
        }
        if (target in plumbing || target ~ /-query$/ || target ~ /^test-/) continue
        if ((own ":" target) in in_src) continue
        print FILENAME ":" FNR ": " s "  <- not required by " own "/src; a fixture needing it is a scenario"
      }
      prev = $0
    }
  ' "${TEST_CLJ[@]}" 2>/dev/null)
fi
report 'brick-test-scope' "$out"

exit "$FAILED"
