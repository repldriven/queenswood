# The chart's version is the release: one number in `version`,
# `appVersion` and every API's OpenAPI `info.version`, bumped together,
# and the merge that bumps it is what builds and publishes the images and
# the chart (.github/workflows/release.yml).
CHART := "infra/helm/queenswood/Chart.yaml"

# Open the pull request that releases `version`, the next patch above origin/main's when empty. Cuts release-<version> off origin/main, or finishes on it after a failed run, and refuses a version already tagged.
release version="":
    #!/usr/bin/env zsh
    set -e
    git fetch --quiet origin main
    CURRENT=$(git show origin/main:{{ CHART }} | awk '/^version:/ {print $2}')
    if [[ -z "$CURRENT" ]]; then
      echo "no version line in {{ CHART }} on origin/main" >&2
      exit 1
    fi
    VERSION="{{ version }}"
    if [[ -z "$VERSION" ]]; then
      VERSION="${CURRENT%.*}.$(( ${CURRENT##*.} + 1 ))"
    fi
    if [[ ! "$VERSION" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
      echo "not a version: '$VERSION' (MAJOR.MINOR.PATCH)" >&2
      exit 1
    fi
    if [[ "$VERSION" == "$CURRENT" || "$(printf '%s\n%s\n' "$CURRENT" "$VERSION" | sort -V | tail -1)" != "$VERSION" ]]; then
      echo "$VERSION is not above $CURRENT, the version on origin/main" >&2
      exit 1
    fi
    if git ls-remote --exit-code --tags origin "refs/tags/v$VERSION" >/dev/null 2>&1; then
      echo "v$VERSION is already released" >&2
      exit 1
    fi
    # A rerun after the commit or the push failed: the branch exists with
    # the edit on it, so redo the edit and finish rather than refusing.
    if [[ "$(git rev-parse --abbrev-ref HEAD)" == "release-$VERSION" ]]; then
      echo "already on release-$VERSION; finishing the release"
    else
      just gh-fresh-branch "release-$VERSION"
    fi
    sed -e "s/^version: .*/version: $VERSION/" \
        -e "s/^appVersion: .*/appVersion: \"$VERSION\"/" {{ CHART }} > {{ CHART }}.tmp
    mv {{ CHART }}.tmp {{ CHART }}
    for f in $(git ls-files 'bases/*/src/**/api.clj'); do
      sed -E "s/:version \"[0-9]+\.[0-9]+\.[0-9]+\"/:version \"$VERSION\"/" "$f" > "$f.tmp"
      mv "$f.tmp" "$f"
    done
    just gh-commit-and-pr "Release $VERSION" \
      "Bumps the chart's version and appVersion, and every API's OpenAPI info.version, from $CURRENT to $VERSION. Merging it is the release: once this commit's Tests are green, the Release workflow builds every image at it, tags them $VERSION, pushes the chart, tags the commit v$VERSION, publishes the GitHub release and deploys its docs to GitHub Pages."
