# Git hooks

<!-- tessl-plugin: workflow -->

## Problem

You want to know what runs when you commit in this repository, why a
commit was refused, and how to arm the hooks in a fresh clone.

## Solution

Three hooks are tracked under `scripts/hooks/` and copied into the
clone's hooks directory by `just install-hooks`, mono's recipe, which
`.envrc` runs on entering the primary checkout after `just mono-import`
has laid it down. The helpers beside them, `check-system-ids.sh` and
`enforce-idioms.sh`, are not hooks and are not installed. The format
and lint steps are mono's too: `pre-commit` sources
`.mono/scripts/hooks/lib.sh` and calls `hook_format` and `hook_lint`
between its own jobs.

`pre-commit` runs these jobs in this order, and the first failure
refuses the commit:

1. Refuse a system identifier in any staged file, per
   [system-identifiers](system-identifiers.md).
2. Format the staged Clojure files with zprint and restage them
   (`hook_format`).
3. Lint the staged Clojure files with clj-kondo (`hook_lint`).
4. Scan the staged Clojure files with mono's semgrep rules and then
   this repository's, in `.mono/.config/semgrep/semgrep.yml` and
   `.config/semgrep/semgrep.yml` — a raw `throw`, a raw time or id
   primitive, `use-fixtures`, `fdb` outside `store.clj`, and the
   rest — each with a per-site `nosemgrep` opt-out (`hook_semgrep`).
5. Run `scripts/hooks/enforce-idioms.sh` over the staged files: the
   guardrails a single file cannot decide, such as a brick test that
   requires another write brick.
6. Lint and template the Helm chart when a file under
   `infra/helm/queenswood/` is staged.

`commit-msg` runs the identifier check over the message, which
`pre-commit` cannot see.

`post-checkout` lays down mono's share of the tree, then installs the
Tessl rules a new worktree does not carry, and never fails the
checkout.

## Failures

- **`pre-commit: .mono/scripts/hooks/lib.sh is missing`** — the hook
  tried to lay mono's share down itself and could not. Run
  `just mono-import` and commit again.
- **`install-hooks: skipped — hooks belong to the whole clone.`** —
  run it from the primary checkout. There is one hooks directory per
  clone, and a linked worktree is not allowed to fill it.
- **A commit is refused with no output after `Checking for system
  identifiers...`** — the identifier check found one in a
  staged file. Run `bash scripts/hooks/check-system-ids.sh --staged`
  to see the line.

## Rules

**MUST:**

- Install the hooks with `just install-hooks` from the primary
  checkout, after `just mono-import`, and again after editing anything
  under `scripts/hooks/`.
- Keep the identifier check first in `pre-commit`, over every staged
  file, and the Clojure jobs in the order format, lint, semgrep,
  guardrails.
- Mark a `throw` that must stay with `;; nosemgrep: no-raw-throw` on
  the line above, and a widened test require with
  `;; enforce-idioms: brick-test-scope -- <reason>`.
- Scan the whole tree with `just semgrep`, and the guardrails with
  `bash scripts/hooks/enforce-idioms.sh --all`.

**MUST NOT:**

- Bypass a hook with `--no-verify` to land a formatting or lint
  failure. CI runs the same checks and rejects the commit.
- Put a check that reads one file's tokens in `enforce-idioms.sh`. It
  belongs in the semgrep rules, where it gets a per-site opt-out — in
  mono's file if any Polylith workspace could run it, here only if it
  needs this workspace's namespaces or layout.

## Discussion

The hook is a sequence of jobs, and the order is the point. The
identifier check runs first and over every staged file, not only
Clojure, because this repository is public and an organisation, folder
or billing account id cannot be unpublished once pushed. The
`commit-msg` hook exists because `pre-commit` reads staged files, so an
identifier written in a message rather than the tree reached `main`
unexamined until it did.

The split between the semgrep rules and `enforce-idioms.sh` is what a
check needs to know. A guardrail decidable from one file's tokens and
its path lives in semgrep, which gives it a per-site `nosemgrep`
opt-out. What lands in the script needs knowledge no single file
carries — a count across the tree, a declaration matched to a
reference in another file, or the name of the brick a file sits in.

Helm validation skips `helm dependency update`, which adds seconds of
network I/O. A prior `just helm-*` recipe populates `charts/`; without
it, `helm template` fails fast naming the missing chart, and
`just helm-validate` syncs it once.

The hook is a local convenience. CI runs the same checks and is the
gate, which is why bypassing the hook only moves the failure.

## References

- [ADR-0012](../../adr/0012-pre-commit-hooks.md) — Pre-commit hooks
  for formatting and linting
- [system-identifiers](system-identifiers.md) — the identifier check and
  the placeholders to write instead
- [git-workflow](git-workflow.md) — the conventions around committing
- [mono-import](mono-import.md) — where the hook library and the
  install recipe come from
