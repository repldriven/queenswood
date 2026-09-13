# queenswood/workflow

Development-workflow automation — how *this team* maintains its own
tooling, as opposed to `queenswood/idioms` (portable Clojure
conventions) or `queenswood/design` (how Queenswood itself is built).
The dividing line: this plugin is about the authoring process, not
about the code the process produces.

## Rule

`rules/workflow.md`: the git hooks as this repository composes them,
and mono's share of the tree. Pulling `main` before committing and how
a `just` recipe is written are mono's `workflow` rule, imported beside
this one.

## Skills

None of its own. The `sync-rules-from-docs` skill that keeps a rule
traceable to its docs ships in mono's `workflow` plugin, laid down
under `.mono/plugins/workflow/` by `just mono-import` and installed
beside this one; its evals live there too.

Planned: migrating the git-workflow-shaped Claude Code skills
(`commit-and-pr`, `fresh-branch`, `check-processors`,
`new-processor`, currently at `.claude/skills/`) into this plugin, so
the dev loop is Tessl-managed and evaluable the same way the Clojure
conventions are.

## Develop

Run `tessl` from this directory. `tessl plugin lint` validates the
package; `tessl skill review skills/<name>` scores a skill for quality
and flags frontmatter issues (including `allowed-tools` — declare the
minimal tool set a skill needs, space-separated, matching the
convention already used across `.claude/skills/`).
