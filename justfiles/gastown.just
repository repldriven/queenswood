# The commands the Gas City polecat lane runs against a work branch.
#
# Every one runs inside the nix devshell. clojure lives only there — it is on
# no profile PATH — and an agent shell cannot load it on its own: there is no
# direnv hook installed, and direnv's zsh hook fires on precmd, which the
# non-interactive `sh -c` an agent runs never reaches.
#
# `nix develop` rather than `direnv exec`, because a fresh agent worktree has an
# untrusted .envrc and direnv refuses there — exactly where the gates must run.
# No --extra-experimental-features needed: nix-command and flakes are enabled
# globally in /etc/nix/nix.conf.
#
# Without this a gate exits 127 for "clojure: command not found", and a wrapper
# whose last statement succeeds reports that as 0. The refinery filed qw-9962
# after concluding "main is clean 3/3" from three runs that executed no tests.
#
# `mol-polecat-work` rebases onto its base branch and then runs a setup,
# lint, and test command taken from the rig's `formula_vars`. Each one
# defaults to empty, which means skip, so a city that names none of them
# gets a lane whose "do not proceed with failures" contract has nothing
# to fail. The factory's city.toml points those variables at the recipes
# here, so the command set is versioned with the code it checks and the
# city holds only the names.

# Delegates to `force-prep`, which is the repo's own forced codegen and
# carries DOMAIN_ALIASES — empty today, but a hook for the bank profile that
# a hand-copied `:aliases '[:dev]'` here would silently drop.
#
# Forced, because a fresh agent worktree carries no `gen/` output — it is
# gitignored, so a new worktree starts without it — while prep's own
# staleness check can still conclude there is nothing to do and leave the
# classpath short of the generated namespaces.

# Run protobuf and malli codegen, unconditionally.
gastown-setup:
    nix develop . --command just force-prep

# Named separately from `test` so the lane has a stable entry point:
# retune `test` and the factory needs no edit.

# Run the test default.
gastown-test:
    nix develop . --command just test

# The hook sees one commit's staged files; a polecat is reviewed on the
# whole branch, so the same checks are scoped to the branch instead.

# Lint, idiom- and semgrep-check the branch's Clojure changes.
gastown-lint base="main":
    nix develop . --command just _gastown-lint {{ base }}

# The body of gastown-lint. Split out so the recipe above can run it inside the
# devshell; clj-kondo and semgrep are both only on the PATH in there.
[private]
_gastown-lint base="main":
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
