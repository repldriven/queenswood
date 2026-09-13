# Queenswood docs conventions

How to write a PRD — the product register on top of mono's `docs` rule,
which covers formatting, link hygiene, the recipe shape and tone for
everything under `docs/`.

## PRDs use the product register

In a PRD, use non-technical product language — never engineering
vocabulary (sync/async, reactive, relay, handler, primitive, changelog,
FDB, message bus, brick) — and never name a specific operation
(`create-organization`); describe what a user does via "the banking
API," not the call it makes, and draw user-visible beats rather than
internal hops in its sequence diagrams. Reserve the project's own
vocabulary (`changelog relay`, `brick`, `interceptor`) for TDDs and
recipes. Keep the competitor names the `check-docs` skill refuses in
`.config/check-docs/names`.
See [writing-prds](../../../docs/recipes/practices/writing-prds.md).
