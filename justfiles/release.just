# The chart's version is the release: one number in `version` and
# `appVersion`, bumped together, and the merge that bumps it is what
# builds and publishes the images and the chart (.github/workflows/release.yml).
CHART := "infra/helm/queenswood/Chart.yaml"

# Open the pull request that releases `version`, the next patch above origin/main's when empty. Cuts release-<version> off origin/main and refuses a version already tagged.
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
    just gh-fresh-branch "release-$VERSION"
    sed -e "s/^version: .*/version: $VERSION/" \
        -e "s/^appVersion: .*/appVersion: \"$VERSION\"/" {{ CHART }} > {{ CHART }}.tmp
    mv {{ CHART }}.tmp {{ CHART }}
    just gh-commit-and-pr "Release $VERSION" \
      "Bumps the chart's version and appVersion from $CURRENT to $VERSION. Merging it is the release: once this commit's Tests are green, the Release workflow builds every image at it, tags them $VERSION, pushes the chart, tags the commit v$VERSION and publishes the GitHub release."
