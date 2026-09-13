# Recipes

Four chapters, by what a recipe is about rather than by which rule
file distils it.

- **code/** — writing Queenswood. mono's recipes for style, helpers,
  error handling and the brick, base, project and system-wiring
  conventions are laid down here by `just mono-import`; Queenswood's
  own cover what sits on top: aggregator bases, library pins, service
  configurations, lifecycle transitions, schema evolution and code
  generation.
- **test/** — how tests drive the system; mono's recipes for the test
  system and the containers it runs against sit beside it.
- **practices/** — working on the repository itself: the git hooks as
  this repository composes them, mono's share of the tree, cloud
  identifiers and naming, and how a PRD is written; mono's recipes for
  git flow, justfile recipes and how these documents are written sit
  beside them.
- **infra/** — everything that runs the bank somewhere: the cloud
  foundation, Crossplane and Argo, credentials and sign-in, the chart,
  and the recovery runbooks.

Every recipe keeps the same shape — `Problem`, `Solution`, an optional
`Failures`, `Rules`, an optional `Discussion`, `References` — and
carries a `<!-- tessl-plugin: <name> -->` label naming the rule file
that distils its `## Rules`.

[CLAUDE.md](../../CLAUDE.md) routes by topic, and is where to start
from what you are trying to do rather than from where it lives.
