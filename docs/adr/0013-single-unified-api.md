# 13. Single unified API for the whole bank

<!-- tessl-plugin: design -->

## Status

Accepted.

## Context

Queenswood exposes a wide range of banking capabilities:
organisations, API keys, parties, identity verification, cash
account products, cash accounts, balances, transactions,
internal/inbound/outbound payments, interest, and policies. Each
is implemented internally by a dedicated processor on the
command pipeline — see the transaction-processing TDD at
[docs/tdd/transaction-processing.md](../tdd/transaction-processing.md).
So the *internal* shape is naturally domain-decomposed.

The question this ADR answers is: should the *external* shape
mirror that internal decomposition? Do consumers of Queenswood
integrate with one API, or with several?

The shortlist:

- **One API for the whole bank.** Single base URL, single
  OpenAPI document, all capabilities under one surface.
- **API per domain.** `accounts-api`, `payments-api`,
  `parties-api`, and so on. Each with its own OpenAPI
  document, its own routing, its own auth surface (or a shared
  auth gateway).
- **API gateway with routing.** One external surface, internal
  routing to multiple domain APIs. Hybrid; still per-domain
  inside.
- **Federated API.** One surface, but each domain owns its
  slice of `/v1/...` independently — separate ownership,
  separate release cadence.

## Decision

We will expose **one HTTP API for the whole bank**, served by
one base (`api`) under one base URL, documented by a
single OpenAPI document. The API surface is *bank-shaped*, not
*implementation-shaped*: a consumer integrates with
"Queenswood", not with "Queenswood-accounts AND
Queenswood-payments AND Queenswood-parties".

Behind that surface:

- The internal decomposition into domain processors lives behind
  the API on the command pipeline.
- Processors can be split, merged, scaled, and reasoned about
  independently inside the system without changing the external
  contract.

## Consequences

Easier:

- **Cross-domain operations are one HTTP call.** Open an
  account and fund it; look up a party with their accounts.
  Server-side composition replaces client-side orchestration.
- **Auth, rate limiting, observability, and error mapping live
  in one place.** No coordination problem between half-similar
  implementations across domains.
- **One source of truth for the contract.** A single OpenAPI
  document; one spec to publish; one set of API conventions
  (error shape, pagination, idempotency, versioning).
- **Consumers integrate with the bank, not its org chart.**
  Fintechs and apps see a coherent banking platform, not a
  service mesh.
- **Internal decomposition stays free.** The command pipeline
  decouples the API from processor scaling and ownership; a
  processor can be split or merged without touching the API.

Harder:

- **Capabilities release together at the API layer.** Adding
  a new endpoint coordinates with everything else under the
  same OpenAPI document. The implementation layer doesn't have
  this constraint, but the surface does.
- **The API base grows.** `api` accumulates routes for
  every capability. Discoverability of code inside the base
  matters more than in a smaller API.
- **No per-domain release cadence at the API surface.** If two
  domains needed to evolve their public contracts on different
  schedules, this would force one to wait. So far the cost has
  been theoretical.

This decision applies only to the *banking* API surface. The
ClearBank simulator and adapter remain their own bases with
their own contracts, because they are deliberately different
surfaces — one mocks an external dependency, the other adapts
to it.

## References

- [ADR-0001](0001-reuse-mono-as-upstream.md) — Reuse mono as upstream
- [docs/tdd/transaction-processing.md](../tdd/transaction-processing.md)
- `api` base
