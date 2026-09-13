# Queenswood dev workflow

Queenswood's conventions on top of mono's `workflow` rule — the local
pre-commit gate as this repository composes it, and mono's share of the
tree.

## The git hooks gate a commit before CI sees it

Install the hooks with `just install-hooks` from the primary checkout,
and again after editing anything under `scripts/hooks/`. `pre-commit`
runs the cloud-identifier check first, over every staged file, then the
Clojure jobs in the order format, lint, semgrep, guardrails; whole-tree
sweeps are `just semgrep` and `enforce-idioms.sh --all`. Mark a `throw`
that must stay with `;; nosemgrep: no-raw-throw` on the line above, and
a widened test require with
`;; enforce-idioms: brick-test-scope -- <reason>`. A check that reads
one file's tokens belongs in the semgrep rules, where it gets a
per-site opt-out, not in `enforce-idioms.sh`. Never bypass a hook with
`--no-verify` to land a formatting or lint failure: CI runs the same
checks and rejects the commit.
Commands: `just install-hooks`, `just semgrep`.
See [git-hooks](../../../docs/recipes/practices/git-hooks.md).

## mono's share of the tree is imported, never committed

The ADRs, recipes, slides, plugins, hook library and justfiles mono owns are
laid down by `just mono-import` at the sha `deps/mono-dev/deps.edn` pins —
untracked, excluded, and reverted to the pinned copy on the next
import. Run it after bumping the pin, then `just tessl-plugins-install`;
`just mono-check` says whether the tree is current. Never edit or
commit an imported file: change it in mono, release, bump. Give a new
ADR a number above both trees and a new recipe a filename neither tree
uses, since the import refuses to overwrite a tracked path. Link an imported doc
from `readme.md` by its mono GitHub URL, never relatively.
Commands: `just mono-import`, `just mono-check`,
`just tessl-plugins-install`.
See [mono-import](../../../docs/recipes/practices/mono-import.md).
