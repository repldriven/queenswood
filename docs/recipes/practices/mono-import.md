# Importing mono's share of the tree

<!-- tessl-plugin: workflow -->

## Status

**Verified** on 2026-09-13, with the commands in the Solution, from a
linked worktree with the dependency checkout present and again with it
absent.

## Problem

You want mono's ADRs, recipes, slide deck, Tessl plugins, hook library, semgrep
rules and justfiles in this tree at the sha `deps/mono-dev/deps.edn` pins,
without committing a copy that could drift from the code it describes.

## Solution

### Prerequisites

A clone with `just` on PATH, and either a tree that has resolved its
Clojure dependencies or network access to GitHub. Start at step 1 in a
tree you have just cloned or added as a worktree; start at step 3 after
bumping the pin.

1. Lay the share down:

   ```
   just mono-import
   ```

   Every file laid down or replaced is printed, `+` for new and `~` for
   replaced. No output means the tree was already current.

2. Confirm the tree is current:

   ```
   just mono-check
   ```

   The output names the pinned sha and the number of files.

3. After a bump, edit the tag and sha in `deps/mono-dev/deps.edn` — and
   in the four code shims beside it, `mono`, `mono-test`,
   `mono-test-runner` and `mono-build`, unless the release changed only
   the practices — then:

   ```
   just mono-import
   just tessl-plugins-install
   ```

   Commit the shims and `tessl.json`. Nothing the import laid down is
   committed.

4. To remove everything the import laid down:

   ```
   just mono-clean
   ```

## Failures

- **`pre-commit: .mono/scripts/hooks/lib.sh is missing`** — the hook
  could not lay the share down on its own, usually because the tree has
  neither a resolved dependency checkout nor network. Run
  `just mono-import` and commit again.
- **`Justfile does not contain recipe install-hooks`** — the recipe is
  one the import lays down. Run `just mono-import` first.
- **`refusing to overwrite tracked docs/adr/00NN-...`** — an ADR number
  is used in both repositories. Renumber the Queenswood one above both
  trees.
- **`refusing to overwrite tracked docs/recipes/<chapter>/<name>.md`** — a
  recipe filename is used in both repositories. Rename the Queenswood
  one.
- **`refusing to overwrite docs/adr/00NN-..., which no import laid down`**
  — a file at an imported path was written by hand. Remove it and import
  again.
- **`imported <sha>, pinned <sha>`** from `just mono-check` — the pin
  moved since the last import. Run `just mono-import`.

## Rules

**MUST:**

- Run `just mono-import` after bumping `deps/mono-dev/deps.edn`, and
  `just tessl-plugins-install` after it, so mono's rules are reinstalled
  at the new sha.
- Take a new ADR's number above the highest in both trees, and a new
  recipe's filename from neither.
- Check a tree with `just mono-check` before trusting its docs, hooks or
  rules.

**MUST NOT:**

- Edit or commit a file the import laid down. Change it in mono,
  release, and bump.
- Link an imported doc from `readme.md` relatively. Use its mono GitHub
  URL: GitHub renders a link to an untracked file as a 404.

## Discussion

The import copies seven subtrees out of mono at the pinned sha: the
ADRs, the recipes, the slide deck, the plugins, the hook scripts, the
semgrep rules and the justfiles.
It reads the sha from `deps/mono-dev/deps.edn`, a shim beside the four
that pin the code, so the practices are a visible dependency with a
version of their own. Nothing puts that shim on a classpath, which is
what lets it lead or trail the code shims when a release changes only
one of the two; kept on the same tag, the docs and rules in the tree are
those of the mono version on the classpath.

Two sources serve it. The first is the checkout `tools.deps` makes
under `~/.gitlibs` when `.envrc` resolves the dependencies: offline,
and exactly the pinned tree. The second is GitHub's archive of the sha,
fetched once into the user cache, for a tree without a JVM — the CI
jobs that bundle `docs/` into the console take this path.

The ADRs, the recipes and the deck land where Queenswood's own would, so a
relative link under `docs/`, in CLAUDE.md and in the rule files resolves without
change, except one from an imported recipe to a mono document outside those
subtrees: the PRD and TDD its writing recipes cite as worked examples resolve in
mono alone. Everything else lands under `.mono/`, where nothing tracked can be
overwritten: mono's plugins carry the same names as Queenswood's, and
`install-hooks` must not shadow the tracked hooks.

`docs/slides/` and `.mono/` are wholly mono's and sit in `.gitignore`.
The ADRs and recipes share `docs/adr/` and `docs/recipes/` with
Queenswood's, so the import excludes them one by one in the clone's
`.git/info/exclude`, regenerating its block from the manifest on every
run. A tracked ignore would have to list mono's files, which are mono's
to add and remove.

The import refuses to overwrite a path this tree tracks. The two
repositories share one ADR number space and one recipe namespace, and
the guard is what makes a number or a filename used twice fail in
`just mono-import` and in CI's `versions` job rather than silently
shadow one file with the other. It also refuses a
differing file at an imported path that no earlier import wrote, which
is what a hand-written copy looks like.

A local edit to an imported file is reverted on the next import, and
the `~` line is the only trace. That is the point: the pinned copy is
the only correct one, and a change belongs in mono.

The import runs at every moment a tree can be new or stale: `.envrc` on
entering the primary checkout, after the dependency resolution that
gives it an offline source; `post-checkout` in a fresh worktree, before
the Tessl rules; `pre-commit`, as a last resort before it sources the
hook library; CI's `versions` job as the gate; and the console jobs
before they bundle `docs/`.

`.mono` is spelled in three places — the script, and in the Justfile
both the `import?` lines and the `TESSL_PLUGIN_ROOTS` definition that
overrides the imported default — because an `import?` path is a literal
a variable cannot feed.

## References

- [ADR-0001](../../adr/0001-reuse-mono-as-upstream.md) — Consume mono as
  a pinned dependency, docs and rules included
- [git-hooks](git-hooks.md) — the hooks that source the imported library
- [justfile-recipes](justfile-recipes.md) — the shapes the recipes
  follow
