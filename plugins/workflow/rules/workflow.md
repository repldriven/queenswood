# Queenswood dev workflow

Queenswood's conventions on top of mono's `workflow` rule — the local
pre-commit gate as this repository composes it, and mono's share of the
tree.

## The git hooks gate a commit before CI sees it

Install the hooks with `just install-hooks` from the primary checkout,
after `just mono-import`, and again after editing anything under
`scripts/hooks/`. `pre-commit` runs the system-identifier check first,
over every staged file, then the Clojure jobs in the order format, lint,
semgrep, guardrails; whole-tree sweeps are `just semgrep` and
`enforce-idioms.sh --all`. Mark a `throw` that must stay with
`;; nosemgrep: no-raw-throw` on the line above, and a widened test
require with `;; enforce-idioms: brick-test-scope -- <reason>`. A check
that reads one file's tokens belongs in the semgrep rules, where it gets
a per-site opt-out, not in `enforce-idioms.sh` — in mono's file if any
Polylith workspace could run it, here only if it needs this workspace's
namespaces or layout. Never bypass a hook with `--no-verify` to land a
formatting or lint failure: CI runs the same checks and rejects the
commit.
Commands: `just install-hooks`, `just mono-import`, `just semgrep`.
See [git-hooks](../../../docs/recipes/practices/git-hooks.md).

## mono's share of the tree is imported, never committed

Never edit or commit a file `just mono-import` laid down: change it in
mono, release, and bump. Run `just mono-import` after bumping
`deps/mono-dev/deps.edn`, then `just tessl-plugins-install`, so mono's
rules are reinstalled at the new sha, and check a tree with
`just mono-check` before trusting its docs, hooks or rules. Take a new
ADR's number above the highest in both trees, and a new recipe's
filename from neither. Link an imported doc from `readme.md` by its
mono GitHub URL, never relatively: GitHub renders a link to an
untracked file as a 404.
Commands: `just mono-import`, `just tessl-plugins-install`, `just
mono-check`.
See [mono-import](../../../docs/recipes/practices/mono-import.md).
