# 14. Full OpenAPI 3.x compliance for the API contract

<!-- tessl-plugin: design -->

## Status

Accepted.

## Context

ADR-0013 establishes that Queenswood exposes one API for the
whole bank, documented by a single OpenAPI document. This ADR
answers a follow-up question: *how seriously do we take that
document?*

Clojure APIs have a mixed reputation for OpenAPI fidelity.
Reitit-with-Malli will happily emit an OpenAPI spec from route
definitions, but the default output is usable rather than
compliant. Common gaps:

- **Inline schemas everywhere**, no reusable `components`. Every
  endpoint defines its types from scratch in the path object;
  generators produce repetitive client code, viewers get a wall
  of duplicated definitions.
- **No security schemes.** Auth requirements are documented in
  prose, not as `securitySchemes` plus per-operation `security`
  references. Tooling can't drive an authenticated flow against
  the spec.
- **No examples.** Schemas describe shape, not values. UI tools
  show empty templates; generated mocks are useless.
- **Inconsistent response coverage.** Happy-path responses are
  declared; 4xx/5xx response shapes are not, or they vary
  endpoint to endpoint.
- **Polymorphism handled inconsistently.** Malli unions don't
  always project as proper `oneOf` / discriminator structures
  without help.

A spec with these gaps is worse than no spec — it gives clients
false confidence and breaks codegen / tooling silently.

The shortlist:

- **Don't bother — hand-roll docs.** Markdown reference, no
  spec. Rejected: client SDKs and tooling integrations need a
  machine-readable contract.
- **Use Reitit's default OpenAPI output as-is.** Get something
  for free; live with the gaps. Rejected for the reasons above.
- **Treat OpenAPI 3.x compliance as a deliberate goal.** Invest
  the work to project Malli schemas as proper components,
  declare security schemes, attach examples, cover all
  responses. The spec becomes a first-class contract artifact.

## Decision

We will treat full OpenAPI 3.x compliance as the API contract.

Concretely:

- **Reusable schemas.** Malli schemas for request and response
  bodies are registered as named components and referenced by
  `$ref`. Endpoint definitions point at the components rather
  than inlining shapes.
- **Security schemes.** API-key authentication is declared
  once as a `securitySchemes` entry and applied per-operation
  via `security`. Endpoints that don't require auth opt out
  explicitly.
- **Examples on every operation.** Request and response bodies
  include realistic examples. Examples are reusable
  `components/examples` where the same shape appears across
  endpoints.
- **All responses documented.** 2xx / 4xx / 5xx response shapes
  are declared per operation, with the rejection / error
  shape itself being a reusable component.
- **Polymorphism via `oneOf` + discriminator.** Where Malli
  unions describe variant payloads, the projection uses
  `oneOf` with a `discriminator` field — not an opaque schema
  the spec can't reason about.
- **The spec is verified.** The exported OpenAPI document is
  validated against the OpenAPI 3.x schema in CI, not just
  trusted to be valid because Reitit produced it.
- **Where it lives.** The implementation lives in `api`, using
  Reitit-with-Malli plus our own component-registration helpers. The
  spec is served at the API root path in development and published
  as a static artefact for consumers.

## Consequences

Easier:

- **Codegen works.** Standard tooling (openapi-generator and
  similar) produces sensible client SDKs because the spec uses
  reusable components and discriminators rather than ad-hoc
  inline shapes.
- **API tooling works fully.** Postman, Insomnia, Stoplight —
  all of them drive authenticated flows, render examples,
  and import schemas correctly.
- **The spec is the answer to "what does this endpoint do?"**
  No drift between docs and implementation; no two-source
  problem.
- **New endpoints follow established patterns.** Reusable
  components and security schemes mean adding an endpoint is
  filling in the gaps, not inventing structure.
- **Validation is honest.** The same Malli schema that defines
  the OpenAPI component validates the request at runtime; an
  inbound request that the spec says is invalid genuinely is.

Harder:

- **Discipline cost.** Every endpoint needs request schema,
  response schema for each status family, security spec, and
  examples. None of this is auto-derivable.
- **Polymorphism takes care.** Malli union types don't always
  project as proper `oneOf` + discriminator without explicit
  help. The structure-the-Malli-deliberately work is real.
- **Examples don't write themselves.** Realistic examples are
  hand-authored and need maintenance as schemas evolve.
- **Coverage gaps are easy.** An undocumented 4xx response or
  a missing example breaks downstream tooling silently. The
  CI validation step catches schema-shape errors but not
  coverage gaps.
- **Versioning is a deliberate concern.** Breaking changes to
  a published component need careful coordination; additive
  changes are cheap but require discipline.

## References

- [ADR-0013](0013-single-unified-api.md) —
  Single unified API for the whole bank
- [docs/tdd/transaction-processing.md](../tdd/transaction-processing.md)
- [OpenAPI 3.x specification](https://spec.openapis.org/oas/latest.html)
- `api` base
