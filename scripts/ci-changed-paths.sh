#!/usr/bin/env bash
#
# Classifies the paths changed in a commit range into the buckets CI gates
# on, writing `<bucket>=true|false` to $GITHUB_OUTPUT and stdout.
#
# The single definition of what each tree means, for test.yml and
# release-images.yml both.
#
# Env:
#   BASE  commit to diff from. Empty or all-zero (a new branch, a
#         force-push) means "everything changed".
#   HEAD  commit to diff to. Defaults to HEAD.
#   ALL   set to `true` to force every bucket true, for workflow_dispatch.
#
# Run it locally to see how a range classifies:
#   BASE=origin/main scripts/ci-changed-paths.sh
#
set -euo pipefail

BASE="${BASE:-}"
HEAD="${HEAD:-HEAD}"
ALL="${ALL:-false}"

everything=false
files=""
if [[ "$ALL" == "true" || -z "$BASE" || "$BASE" =~ ^0+$ ]]; then
  everything=true
else
  # From the merge base, not BASE itself: on a pull request BASE is the
  # target branch's tip, whose own commits are not changes here.
  merge_base=$(git merge-base "$BASE" "$HEAD" 2>/dev/null || echo "$BASE")
  files=$(git diff --name-only "$merge_base" "$HEAD")
fi

# The JS-bearing bricks. Everything else under bases/ and components/ is
# Clojure, so these are named once and subtracted below.
CONSOLE_TREES='^(bases/console/|components/ui/|package\.json$|yarn\.lock$|\.yarnrc\.yml$)'
# The demos' customer apps: JS bases beside the console, in no image yet.
DEMO_TREES='^bases/demo-[a-z-]+-app/'
# Dev-only brick: renders docs/diagrams to committed SVGs, ships nowhere.
DIAGRAM_TREES='^components/excalidraw/'

# The Clojure workspace: the bricks, projects and pins, with the JS
# bricks removed. `|| true` because grep exits 1 on no match.
clojure_files=$(grep -vE "$CONSOLE_TREES|$DEMO_TREES|$DIAGRAM_TREES" <<<"$files" || true)

# any <extended-regex> [file-list] — did anything matching change?
any() {
  if [[ "$everything" == "true" ]]; then return 0; fi
  grep -qE "$1" <<<"${2-$files}"
}

emit() {
  echo "$1=$2"
  if [[ -n "${GITHUB_OUTPUT:-}" ]]; then
    echo "$1=$2" >> "$GITHUB_OUTPUT"
  fi
}

bucket() {
  local name=$1 regex=$2 list=${3-$files}
  if any "$regex" "$list"; then emit "$name" true; else emit "$name" false; fi
}

WORKSPACE='^(bases/|components/|deps/|projects/|deps\.edn$|workspace\.edn$|versions\.json$)'

# The docs the console bundles. Landing.svelte `?raw`-imports ADRs, TDDs
# and a recipe, and infra/docker/console/Dockerfile.dockerignore whitelists
# exactly these three subtrees into the image build.
BUNDLED_DOCS='^docs/(adr|tdd|recipes)/'

# mono's share of docs/ comes from the sha deps/mono-dev/deps.edn pins, laid
# down by scripts/mono-import.sh, so either changing changes the bundle.
MONO_IMPORT='^(deps/mono-dev/|scripts/mono-import\.sh$)'

# A release is tested whole. The first green commit on main whose
# declared version has no tag is the one built, tagged and published, so
# while the chart's version is untagged every bucket runs, rather than a
# release inheriting an earlier commit's result through the path filter.
released() {
  local tag="refs/tags/v$1"
  if git remote get-url origin >/dev/null 2>&1; then
    git ls-remote --exit-code --tags origin "$tag" >/dev/null 2>&1
  else
    git show-ref --quiet --verify "$tag"
  fi
}
if [[ "$everything" != "true" ]]; then
  version=$({ git show "$HEAD:infra/helm/queenswood/Chart.yaml" 2>/dev/null || true; } \
              | awk '/^version:/ {print $2}')
  if [[ -n "$version" ]] && ! released "$version"; then
    everything=true
  fi
fi

# Runs the polylith matrix. `development/` carries project:dev's extra
# paths, and scripts/ holds the hooks and check-versions.sh.
bucket clojure "$WORKSPACE|^(development/|scripts/)|^\.github/workflows/test\.yml$" "$clojure_files"

# Builds the console bundle, the ui showcase and the demo apps.
bucket js "$CONSOLE_TREES|$DEMO_TREES|$BUNDLED_DOCS|$MONO_IMPORT|^infra/docker/console/|^\.github/workflows/test\.yml$"

bucket helm '^infra/helm/|^\.github/workflows/test\.yml$'

# Goes into a JVM service image. Not development/ or scripts/: neither is
# on a service project's classpath.
bucket services "$WORKSPACE|^infra/docker/(service/|bake\.hcl$)|^\.github/workflows/release\.yml$" "$clojure_files"

# Goes into the console image.
bucket console "$CONSOLE_TREES|$BUNDLED_DOCS|$MONO_IMPORT|^infra/docker/console/|^infra/docker/bake\.hcl$|^\.github/workflows/release\.yml$"
