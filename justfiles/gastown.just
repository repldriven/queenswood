# The commands the Gas City polecat lane runs against a work branch.
#
# `mol-polecat-work` rebases onto its base branch and then runs a setup,
# lint, and test command taken from the rig's `formula_vars`. Each one
# defaults to empty, which means skip, so a city that names none of them
# gets a lane whose "do not proceed with failures" contract has nothing
# to fail. The factory's city.toml points those variables at the recipes
# here, so the command set is versioned with the code it checks and the
# city holds only the names.

# `:force true` because a fresh agent worktree carries no `gen/` output —
# it is gitignored, so a new worktree starts without it — while prep's
# own staleness check can still conclude there is nothing to do and leave
# the classpath short of the generated namespaces.

# Run protobuf and malli codegen, unconditionally.
gastown-setup:
    clj -X:deps prep :aliases '[:dev]' :force true

# Named separately from `test` so the lane has a stable entry point:
# retune `test` and the factory needs no edit.

# Run the full suite, bringing docker up first.
gastown-test: test

# The hook sees one commit's staged files; a polecat is reviewed on the
# whole branch, so the same checks are scoped to the branch instead.

# Lint, idiom- and semgrep-check the branch's Clojure changes.
gastown-lint base="main":
    #!/usr/bin/env bash
    set -euo pipefail
    bash scripts/hooks/enforce-idioms.sh --branch
    # Deletions are excluded by --diff-filter rather than by testing
    # each path, and `|| true` absorbs grep's empty-match 1 — under
    # `set -e` a branch with no Clojure changes would otherwise fail
    # here rather than pass.
    files=$(git diff --name-only --diff-filter=ACMR origin/{{ base }}...HEAD \
              -- '*.clj' '*.cljc' '*.cljs' \
            | grep -Ev '(^|/)evals/' || true)
    if [ -z "$files" ]; then
      echo "No Clojure files changed against origin/{{ base }}."
      exit 0
    fi
    echo "$files" | xargs clojure -M:lint/clj-kondo --lint
    echo "$files" | xargs semgrep --config .config/semgrep/semgrep.yml \
      --error --disable-version-check
