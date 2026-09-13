#!/usr/bin/env bash
# Lay down mono's share of this tree at the sha deps/mono-dev/deps.edn pins.
# See docs/recipes/practices/mono-import.md.
#
#   scripts/mono-import.sh          import; stdout lists what changed
#   scripts/mono-import.sh --check  exit 1 when behind the pin or incomplete
#   scripts/mono-import.sh --clean  remove everything it laid down
#
# Env: GITLIBS, XDG_CACHE_HOME, MONO_ARCHIVE (tarball URL base).
set -euo pipefail
cd "$(git rev-parse --show-toplevel)"

MONO_ROOT=.mono
MANIFEST=$MONO_ROOT/manifest
SHA_FILE=$MONO_ROOT/SHA
SOURCE_FILE=$MONO_ROOT/SOURCE
PIN=deps/mono-dev/deps.edn
EXCLUDE=$(git rev-parse --git-path info/exclude)
MARK_BEGIN='# >>> mono-import: written by scripts/mono-import.sh, do not edit >>>'
MARK_END='# <<< mono-import <<<'

# "<mono subtree> <destination>". Docs land in place so links hold; the
# rest lands under the mirror root.
SUBTREES=(
  "docs/adr docs/adr"
  "docs/recipes docs/recipes"
  "docs/slides docs/slides"
  "plugins $MONO_ROOT/plugins"
  "scripts/hooks $MONO_ROOT/scripts/hooks"
  "justfiles $MONO_ROOT/justfiles"
  ".config/semgrep $MONO_ROOT/.config/semgrep"
)

die() {
  echo "mono-import: $*" >&2
  exit 1
}

pinned_sha() {
  local sha
  sha=$(sed -nE 's/.*:git\/sha "([0-9a-f]{40})".*/\1/p' "$PIN" | head -1)
  [ -n "$sha" ] || die "no :git/sha in $PIN"
  echo "$sha"
}

# The tools.deps checkout when it exists; otherwise the archive, fetched
# once into the cache.
source_tree() {
  local sha=$1 dir
  dir=${GITLIBS:-$HOME/.gitlibs}/libs/com.repldriven/mono/$sha
  if [ -f "$dir/deps.edn" ]; then
    echo "$dir"
    return
  fi
  dir=${XDG_CACHE_HOME:-$HOME/.cache}/queenswood/mono/$sha
  if [ ! -f "$dir/deps.edn" ]; then
    rm -rf "$dir.part"
    mkdir -p "$dir.part"
    curl -fsSL "${MONO_ARCHIVE:-https://github.com/repldriven/mono/archive}/$sha.tar.gz" \
      | tar -xzf - -C "$dir.part" --strip-components=1
    mv "$dir.part" "$dir"
  fi
  echo "$dir"
}

# The files under one subtree of the source, relative to its root.
list_tree() {
  local src=$1 subtree=$2
  [ -e "$src/$subtree" ] || return 0
  if [ -e "$src/.git" ]; then
    git -C "$src" ls-files -- "$subtree"
  else
    (cd "$src" && find "$subtree" -type f)
  fi
}

# "<source path><TAB><destination>" for every file to lay down.
plan_files() {
  local src=$1 entry from to rel
  for entry in "${SUBTREES[@]}"; do
    from=${entry% *}
    to=${entry#* }
    list_tree "$src" "$from" | while IFS= read -r rel; do
      [ -n "$rel" ] || continue
      case "$from" in
        docs/adr|docs/recipes) [[ "$rel" == *.md ]] || continue ;;
      esac
      printf '%s\t%s\n' "$rel" "$to/${rel#"$from"/}"
    done
  done | LC_ALL=C sort -t "$(printf '\t')" -k2,2
}

# Destinations read from stdin; the ones under docs/adr/ and docs/recipes/
# form the block, anchored so each pattern matches that one path.
write_exclude() {
  local tmp paths
  tmp=$(mktemp)
  mkdir -p "$(dirname "$EXCLUDE")"
  if [ -f "$EXCLUDE" ]; then
    awk -v b="$MARK_BEGIN" -v e="$MARK_END" '
      $0 == b { skip = 1 }
      !skip { print }
      $0 == e { skip = 0 }
    ' "$EXCLUDE" > "$tmp"
  fi
  paths=$(grep -E '^docs/(adr|recipes)/' || true)
  if [ -n "$paths" ]; then
    {
      echo "$MARK_BEGIN"
      printf '%s\n' "$paths" | sed 's|^|/|'
      echo "$MARK_END"
    } >> "$tmp"
  fi
  mv "$tmp" "$EXCLUDE"
}

do_import() {
  local sha src plan old_manifest rel dst stale
  sha=$(pinned_sha)
  src=$(source_tree "$sha")
  plan=$(plan_files "$src")
  [ -n "$plan" ] || die "nothing to import from $src"
  old_manifest=""
  if [ -f "$MANIFEST" ]; then
    old_manifest=$(cat "$MANIFEST")
  fi

  # Refuse before any write: a destination this repository tracks, or a
  # differing file present that no earlier import laid down.
  while IFS=$'\t' read -r rel dst; do
    if git ls-files --error-unmatch -- "$dst" >/dev/null 2>&1; then
      die "refusing to overwrite tracked $dst: renumber or remove it"
    fi
    if [ -e "$dst" ] && ! grep -qxF "$dst" <<<"$old_manifest" \
       && ! cmp -s "$src/$rel" "$dst"; then
      die "refusing to overwrite $dst, which no import laid down"
    fi
  done <<<"$plan"

  cut -f2 <<<"$plan" | write_exclude

  while IFS=$'\t' read -r rel dst; do
    if [ -e "$dst" ] && cmp -s "$src/$rel" "$dst"; then
      continue
    fi
    mkdir -p "$(dirname "$dst")"
    if [ -e "$dst" ]; then
      echo "~ $dst"
    else
      echo "+ $dst"
    fi
    cp "$src/$rel" "$dst"
  done <<<"$plan"

  stale=$(comm -23 <(printf '%s\n' "$old_manifest" | LC_ALL=C sort) \
                   <(cut -f2 <<<"$plan" | LC_ALL=C sort) | grep -v '^$' || true)
  if [ -n "$stale" ]; then
    while IFS= read -r dst; do
      [ -e "$dst" ] || continue
      echo "- $dst"
      rm -f "$dst"
    done <<<"$stale"
    find docs/slides "$MONO_ROOT" -type d -empty -delete 2>/dev/null || true
  fi

  mkdir -p "$MONO_ROOT"
  cut -f2 <<<"$plan" > "$MANIFEST.tmp"
  mv "$MANIFEST.tmp" "$MANIFEST"
  echo "$src" > "$SOURCE_FILE"
  echo "$sha" > "$SHA_FILE"
  echo "mono-import: $sha from $src" >&2
}

do_check() {
  local sha dst missing=0
  sha=$(pinned_sha)
  [ -f "$SHA_FILE" ] || die "not imported: run just mono-import"
  [ "$(cat "$SHA_FILE")" = "$sha" ] \
    || die "imported $(cat "$SHA_FILE"), pinned $sha: run just mono-import"
  while IFS= read -r dst; do
    [ -n "$dst" ] || continue
    if [ ! -f "$dst" ]; then
      echo "mono-import: missing $dst" >&2
      missing=1
    fi
  done < "$MANIFEST"
  [ "$missing" -eq 0 ] || die "incomplete: run just mono-import"
  grep -qF "$MARK_BEGIN" "$EXCLUDE" 2>/dev/null \
    || die "exclude block missing from $EXCLUDE: run just mono-import"
  echo "mono-import: $sha, $(grep -c '' "$MANIFEST") files"
}

do_clean() {
  local dst
  if [ -f "$MANIFEST" ]; then
    while IFS= read -r dst; do
      [ -n "$dst" ] || continue
      rm -f "$dst"
    done < "$MANIFEST"
  fi
  find docs/slides "$MONO_ROOT" -type d -empty -delete 2>/dev/null || true
  rm -rf "$MONO_ROOT"
  write_exclude </dev/null
  echo "mono-import: cleaned" >&2
}

case "${1:-}" in
  "") do_import ;;
  --check) do_check ;;
  --clean) do_clean ;;
  *) die "usage: scripts/mono-import.sh [--check|--clean]" ;;
esac
