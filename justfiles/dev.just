# Check the native toolchain on PATH against versions.json. The nix devshell
# provides these; this is what tells you what is wrong when it is not active.
doctor:
    #!/usr/bin/env bash
    set -uo pipefail
    fail=0
    check() {
      local name="$1" want="$2" got="$3"
      if [ -z "$got" ]; then
        printf '  %-19s \033[31mmissing\033[0m (need %s)\n' "$name" "$want"
        fail=1
      elif [ "$got" = "$want" ]; then
        printf '  %-19s \033[32m%s\033[0m\n' "$name" "$got"
      else
        printf '  %-19s \033[31m%s\033[0m (need %s)\n' "$name" "$got" "$want"
        fail=1
      fi
    }
    echo "Native toolchain, against versions.json:"
    check foundationdb \
      "$(jq -r '.foundationdb.version' versions.json)" \
      "$(fdbcli --version 2>/dev/null | sed -n '1s/.*(v\([0-9.]*\)).*/\1/p')"
    check protoc \
      "$(jq -r '.protoc.version' versions.json)" \
      "$(protoc --version 2>/dev/null | awk '{print $2}')"
    check protoc-gen-clojure \
      "$(jq -r '."protoc-gen-clojure".version' versions.json)" \
      "$(protoc-gen-clojure -v 2>/dev/null | sed -n 's/.*v\([0-9.]*\).*/\1/p')"
    if [ "$fail" -ne 0 ]; then
      echo
      echo "Enter the nix devshell to get these, or install them at the"
      echo "versions above. A mismatched FoundationDB client cannot talk to"
      echo "the test cluster, and a newer protoc emits protobuf 4 code that"
      echo "the pinned FDB Record Layer cannot load."
      exit 1
    fi
    echo
    just check-versions

# Check the pinned versions that cannot read versions.json -- Dockerfile ARG
# defaults and Helm values -- still agree with it. Also runs in CI.
check-versions:
    ./scripts/check-versions.sh

# Start nREPL server for Conjure connection
repl:
    find . -name .nrepl-port -not -path ./.nrepl-port -delete
    clojure -M{{ DOMAIN_ALIASES }}:dev:test:nrepl -Sforce -P

# Start Rebel Readline REPL with colors and completion
rebel:
    clj -M{{ DOMAIN_ALIASES }}:dev:test:rebel

# Start polylith shell
shell:
    clj -M:poly shell

# Run Eastwood static analysis
lint-eastwood:
    clojure -M{{ DOMAIN_ALIASES }}:dev:test:lint/eastwood

# Run clj-kondo linter across all bricks and config files
lint-clj-kondo:
    clojure -M:lint/clj-kondo --lint bases components projects deps.edn workspace.edn

# Run all linters (Eastwood + clj-kondo)
lint:
  just lint-eastwood
  just lint-clj-kondo

# Install the git hooks (run again whenever a hook itself changes)
install-hooks:
    #!/usr/bin/env zsh
    set -euo pipefail
    # git resolves this for a worktree too, where .git is a file and the real
    # hooks directory lives in the primary checkout — so one install covers
    # every worktree of this clone, including the ones Gas City has yet to
    # create. That is what makes post-checkout reach them.
    for name in pre-commit commit-msg post-checkout; do
      hook=$(git rev-parse --git-path "hooks/$name")
      mkdir -p "$(dirname "$hook")"
      cp "scripts/hooks/$name" "$hook"
      chmod +x "$hook"
      echo "installed $hook"
    done

# Scan for semgrep guardrails (no args = all bricks, or pass paths)
semgrep *paths="bases components projects":
    semgrep --config .config/semgrep/semgrep.yml --error --disable-version-check {{ paths }}

# Static-analysis metrics across all brick src dirs (cyclomatic, Halstead, MI).
metrics:
    #!/usr/bin/env zsh
    set -euo pipefail
    {
      for d in components bases; do
        clojure -M:metrics -p "$d" \
          | tail -1 \
          | jq -c '.files[] | select(.file | test("/src/"))'
      done
    } | jq -s 'sort_by(-.["cyclomatic-complexity"]) | {count: length, files: .}'

# Top N most complex files. Cyclo: 1-10 simple, 11-20 moderate, 21-50 complex,
# >50 very complex. MI: >=85 excellent, 70-84 good, 50-69 moderate, 25-49 poor,
# <25 critical. Colors only render when stdout is a TTY.
metrics-top n="20":
    #!/usr/bin/env zsh
    set -euo pipefail
    if [[ -t 1 ]]; then
      G=$'\e[32m' Y=$'\e[33m' C=$'\e[36m' R=$'\e[31m' BR=$'\e[1;31m' Z=$'\e[0m'
    else
      G= Y= C= R= BR= Z=
    fi
    cyclo_c() {
      if   (( $1 <= 10 )); then echo $G
      elif (( $1 <= 20 )); then echo $Y
      elif (( $1 <= 50 )); then echo $R
      else                      echo $BR
      fi
    }
    mi_c() {
      if   (( $1 >= 85 )); then echo $G
      elif (( $1 >= 70 )); then echo $C
      elif (( $1 >= 50 )); then echo $Y
      elif (( $1 >= 25 )); then echo $R
      else                      echo $BR
      fi
    }
    printf '%-5s  %-3s  %-5s  %s\n' cyclo mi lloc file
    just metrics \
      | jq -r '.files[:{{ n }}][] | [.["cyclomatic-complexity"], (.maintainability.index|floor), .length.lloc, .file] | @tsv' \
      | while IFS=$'\t' read -r cyclo mi lloc file; do
          printf '%s%-5s%s  %s%-3s%s  %-5s  %s\n' \
            "$(cyclo_c $cyclo)" "$cyclo" "$Z" \
            "$(mi_c $mi)"       "$mi"    "$Z" \
            "$lloc" "$file"
        done

# Workspace metrics headline (counts, totals, averages, worst)
metrics-summary:
    #!/usr/bin/env zsh
    set -euo pipefail
    just metrics | jq '{
      files:       .count,
      total_cyclo: ([.files[].["cyclomatic-complexity"]] | add),
      worst_cyclo: ([.files[].["cyclomatic-complexity"]] | max),
      avg_cyclo:   (([.files[].["cyclomatic-complexity"]] | add) / .count),
      total_lloc:  ([.files[].length.lloc] | add),
      avg_mi:      (([.files[].maintainability.index] | add) / .count),
      worst_mi:    ([.files[].maintainability.index] | min)
    }'

# Format all Clojure source files with zprint
format:
    #!/usr/bin/env bash
    set -e
    echo "Formatting Clojure source files..."
    files=$(git ls-files '*.clj' '*.cljc' '*.cljs' '*.edn' | while read f; do [ -f "$f" ] && echo "$f"; done)
    if [ -n "$files" ]; then
        echo "$files" | xargs clojure -M:format/zprint '{:search-config? true}' -w
        echo "✓ Formatting complete"
    else
        echo "No Clojure files found"
    fi
