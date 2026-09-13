# Writing PRDs

<!-- tessl-plugin: docs -->

## Problem

You're writing or editing a product requirements document under
`docs/prd/`, and you want it readable by the people it is for.

## Solution

PRDs are read by product-shaped readers — product managers, designers,
compliance, executives — not engineers. Two rules follow, on top of
[writing-docs](writing-docs.md), which covers everything under `docs/`.

### Use non-technical product language

Engineering vocabulary doesn't belong in a PRD even when it's accurate.

- Avoid: synchronous, asynchronous, reactive, primitive, watcher,
  relay, handler, idempotent, deterministic, transaction (in the
  engineering sense), event, dispatch, subscribe, poll (as a verb of
  art), choreography, saga, orchestrator.
- Prefer: "in the background", "automatically", "without the customer
  having to do anything", "once X completes", "the platform offers a
  way to", "a means of comparing".
- "Atomic" is borderline — OK if framed as "all-or-nothing" with a
  quick gloss; better to say "either the account comes up complete or
  doesn't come up at all".
- Internal-mechanism words (changelog, FDB, message bus, brick) never
  belong in a PRD.
- Sequence diagrams in PRDs describe user-visible beats, not internal
  hops between components.

```
;; Not OK — engineering register
The IDV flow is asynchronous and reactive. A name-matching
primitive lets callers compare names. A relay publishes an
event when the changelog updates.

;; OK — product register
Identity verification runs in the background; the customer
doesn't wait for it. The platform offers a way to compare two
names softly. Activation happens automatically once
verification completes.
```

### Describe what users do, not the operation name

PRDs say "uses the banking API to X" — they don't name specific
operations like `create-organization` or `submit-payment`. Operation
names belong in TDDs and the OpenAPI spec.

```
;; Not OK
The platform exposes a `create-organization` operation.
The admin calls `create-organization` with name, type, ...

;; OK
A platform admin uses the banking API to create a new organisation
in a single call. The call accepts the organisation's name,
type, ...
```

Inputs and outputs to a call can still be listed, framed as "the call
accepts" / "the call returns" — that's user-relevant without naming the
operation.

### The names the checker refuses

The banks and fintechs no doc may name are listed one per line in
[names](/.config/check-docs/names), which the `check-docs` skill reads.
Add a name there when one turns up in a draft.

## Rules

**MUST:**

- Use non-technical product language in a PRD, and describe what a
  user does via "the banking API" rather than the call it makes.
- Keep the competitor names the `check-docs` skill refuses in
  `.config/check-docs/names`.

**MUST NOT:**

- Use engineering vocabulary (sync/async, reactive, relay, handler,
  primitive) in PRDs.
- Name specific operations (`create-organization`, `submit-payment`,
  etc.) in PRDs.
- Draw internal hops between components in a PRD's sequence diagram.

**SHOULD:**

- Use the project's vocabulary in TDDs and recipes (`changelog relay`,
  `brick`, `interceptor`); reserve product-shaped phrasing for PRDs.

## Discussion

The PRD-vs-TDD split is the rule with the highest payoff. A PRD that
names operations and uses engineering vocabulary turns the document
into a TDD-with-different-headers, and the audience it's meant to serve
can't read it without translation. Forcing the register keeps the
documents distinct and readable to their respective audiences. The TDD
covers the same ground, names the operations, and is the engineering
contract, which leaves the PRD free to be product-shaped.

## References

- [writing-docs](writing-docs.md) — everything under `docs/`, mono's
  recipe
- [platform](../../prd/platform.md) — the personas every PRD writes for
