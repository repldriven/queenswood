# Tessl plugins: the rules an agent loads, and the copy it loads them
# from.
#
# `plugins/<name>/rules/<name>.md` is the tracked source. AGENTS.md
# loads `.tessl/RULES.md`, which points at
# `.tessl/plugins/queenswood/<name>/rules/<name>.md` -- a gitignored,
# per-machine copy that only an install refreshes. Editing the source
# alone changes nothing any agent reads, and shows no diff, which is
# what `tessl-plugins-check` is for.
#
# A fresh worktree has no copy at all, and memory is read before any
# session hook runs, so the install has to happen when the tree is
# created. The `post-checkout` hook does that -- `just install-hooks`.
#
# Every plugin stays installed. A profile chooses which of them
# `.tessl/RULES.md` links, so an agent loads the rules its task needs
# and none of the rest. A profile is read at session start, so a change
# reaches the next agent, not the one that made it.

# Plugins a profile links, in the order `tessl install` writes them.
TESSL_PROFILE_DEFAULT := "design docs framework idioms workflow"
TESSL_PROFILE_INFRA := "deployment workflow"
TESSL_PROFILE_ALL := "deployment design docs framework idioms workflow"

# Install every plugin from the working tree, not the registry.
tessl-plugins-install:
    #!/usr/bin/env zsh
    set -e
    for d in ./plugins/*/; do
      tessl install "file:${d%/}"
    done
    PROFILE=default
    if [[ -f .tessl/PROFILE ]]; then
      PROFILE=$(cat .tessl/PROFILE)
    fi
    # tessl install rewrites RULES.md with every plugin, so the active
    # profile has to be laid back down after it.
    just tessl-profile name="$PROFILE"

# Link one profile's rules from .tessl/RULES.md. name: default, infra, all.
tessl-profile name="default":
    #!/usr/bin/env zsh
    set -e
    # `just tessl-profile name=infra` passes the whole `name=infra` as
    # the argument, so both that and the bare `infra` have to land here.
    NAME="{{ name }}"
    NAME="${NAME#name=}"
    case "$NAME" in
      default) PLUGINS=({{ TESSL_PROFILE_DEFAULT }}) ;;
      infra)   PLUGINS=({{ TESSL_PROFILE_INFRA }}) ;;
      all)     PLUGINS=({{ TESSL_PROFILE_ALL }}) ;;
      *)
        echo "unknown profile: $NAME (default, infra, all)"
        exit 1
        ;;
    esac
    if [[ ! -d .tessl/plugins/queenswood ]]; then
      echo "no installed plugins: run just tessl-plugins-install"
      exit 1
    fi
    MISSING=()
    for P in $PLUGINS; do
      if [[ ! -f .tessl/plugins/queenswood/$P/rules/$P.md ]]; then
        MISSING+=($P)
      fi
    done
    if (( ${#MISSING[@]} > 0 )); then
      echo "not installed: $MISSING"
      echo "Run: just tessl-plugins-install"
      exit 1
    fi
    {
      echo "# Agent Rules"
      echo
      echo "This file is updated when running \`tessl install\`. If a linked file is missing, make sure to run the command to download any missing plugins from the registry."
      for P in $PLUGINS; do
        echo
        echo "## queenswood/$P — $P"
        echo
        echo "@plugins/queenswood/$P/rules/$P.md [$P](plugins/queenswood/$P/rules/$P.md)"
      done
    } > .tessl/RULES.md
    echo "$NAME" > .tessl/PROFILE
    echo "profile $NAME: $PLUGINS"
    echo "Agents pick this up on their next session, not this one."

# Which profile is loaded, and what it costs to load it.
tessl-profile-status:
    #!/usr/bin/env zsh
    set -e
    if [[ ! -f .tessl/PROFILE ]]; then
      echo "no profile: run just tessl-profile name=default"
      exit 0
    fi
    PROFILE=$(cat .tessl/PROFILE)
    echo "profile: $PROFILE"
    for P in $(grep -oE '^## queenswood/[a-z-]+' .tessl/RULES.md | cut -d/ -f2); do
      SIZE=$(wc -c < ".tessl/plugins/queenswood/$P/rules/$P.md")
      printf "  %-12s %6d chars\n" "$P" "$SIZE"
    done
    TOTAL=$(cat .tessl/RULES.md | grep -oE '^@plugins/\S+' | sed 's|^@|.tessl/|' | xargs wc -c | tail -1 | awk '{print $1}')
    echo "  total        $TOTAL chars"

# Whether the installed rules match the tracked sources.
tessl-plugins-check:
    #!/usr/bin/env zsh
    set -e
    # A clone that has never installed has nothing to compare, which is
    # not drift.
    if [[ ! -d .tessl/plugins/queenswood ]]; then
      echo "no installed plugins: run just tessl-plugins-install"
      exit 0
    fi
    STALE=()
    for SRC in plugins/*/rules/*.md; do
      NAME=$(basename "$SRC")
      DEST=".tessl/plugins/queenswood/${NAME%.md}/rules/$NAME"
      if [[ ! -f $DEST ]]; then
        STALE+=("$NAME (never installed)")
      elif ! diff -q "$SRC" "$DEST" >/dev/null; then
        STALE+=("$NAME")
      fi
    done
    if (( ${#STALE[@]} == 0 )); then
      echo "installed rules match their sources"
      exit 0
    fi
    for S in $STALE; do
      echo "stale: $S"
    done
    echo
    echo "The rules agents load are behind the tree."
    echo "Run: just tessl-plugins-install"
    exit 1
