# 20. External providers are deployment facts, not request parameters

<!-- tessl-plugin: design -->

## Status

Accepted.

## Context

Queenswood talks to three external providers: ClearBank for payments,
Onfido for identity verification, and UK Companies House for company
lookups. Two of them arrived with the same shape and one did not.

ClearBank and Onfido each split three ways. A vendor-named adapter base
(`clearbank-adapter`, `onfido-adapter`) owns the HTTP surface in both
directions and consumes commands. A vendor-named relay component
(`clearbank-relay`, `onfido-relay`) owns egress: the intent store and
the outbound calls. A provider-agnostic domain component (`payment`,
`idv`) owns the concept, and never names a vendor. Nothing in the
request identifies the provider, because nothing needs to: which
provider answers is settled by which adapter service is running and
which URL it is configured with.

Companies House arrived inverted. One component, `company-registry`,
held the vendor HTTP client, the FDB store, and a registry abstraction
on top of both. Callers passed a `registry-id`, which was validated
against an `available-registries` list holding exactly one entry, while
config carried a single hardcoded `companies-house-url`. The id was a
path segment, an Avro request field, and a value echoed back on the
reply so the caller could learn what a blank one had defaulted to.

The abstraction promised pluggability it could not deliver. Adding a
second registry meant editing the component: extending the list,
branching the fetch, and threading a second URL through config. Every
caller paid for a choice no caller could make.

The shortlist:

- **Complete it.** Make `available-registries` real: per-registry
  config, a fetch implementation per registry, dispatch on the id.
  This builds a plugin system inside one brick to solve a problem the
  message bus already solves outside it.
- **Leave it half-built.** The status quo. The cost is not the dead
  validation but the false promise — the shape tells every reader that
  registry is a caller's choice, so new code keeps threading it.
- **Collapse it.** Delete the parameter and let the provider be
  whatever the running adapter is, matching ClearBank and Onfido.

## Decision

An external provider is a deployment fact. It is chosen by which adapter service
runs and how that service is configured — never by a request parameter validated
against a list.

Concretely:

- Pluggability is served by the command channel, which already provides it. A
  second company registry is a second adapter base consuming
  `companies-command`, deployed instead of or alongside the first. The routing
  question is a deployment question, answered by configuration and consumer
  groups, not by a conditional inside a brick.
- A domain component (`company`, `idv`, `payment`) never names a provider and
  never takes a provider parameter.
- A vendor's HTTP contract — the outbound call and the translation of its wire
  shape — lives in that vendor's adapter, which is the only thing named after
  it.
- Anomaly kinds stay provider-neutral even when raised inside a vendor's
  adapter. They surface as the API's RFC 9457 `type` (ADR-0014), which is a
  public contract and must not name a supplier.
- A provider identity may still be **recorded**, and that is not a violation.
  The bank's `CompanyBinding.registry` is written once at onboarding and never
  read back for dispatch: it says which register the entity was confirmed
  against, which is a fact about the bank worth keeping. The adapter stamps its
  own identity onto its reply and the caller snapshots it. Provenance is an
  output; nothing branches on it.
- The distinction is the test to apply. If a value selects behaviour, it is
  dispatch, and it belongs in deployment. If it only records what happened, it
  is provenance, and it can travel on the reply.

## Consequences

Easier:

- One shape for all three providers. A reader who has understood the
  Onfido path has understood the Companies House path.
- Adding a registry is a deployment exercise, not a component edit. No
  brick changes, no list to extend, no branch to add.
- Callers get smaller. The route lost a path segment, the command lost
  an Avro field, and the onboarding body lost an optional key that no
  client could usefully vary.
- Errors stop leaking suppliers. A client that sees
  `:company/not-found` learns nothing about who Queenswood asked, which
  is what lets the supplier change without an API change.

Harder:

- Running two registries at once needs real routing — separate channels
  per registry, or a dispatch key the platform owns. That design is
  deferred until a second registry exists, deliberately: the shape it
  should take depends on whether registries are picked per-jurisdiction,
  per-tenant, or per-request, and guessing produced the abstraction this
  ADR removes.
- The provenance carve-out is a judgement call at each use. `registry`
  on the binding is provenance; a `registry` that chose the fetch would
  not be. Reviewers have to apply the dispatch-versus-record test rather
  than pattern-match on the field name.
- Collapsing a public parameter is a breaking change.
  `OnboardingRequest` is `:closed true`, so a body still carrying
  `registry` is now rejected rather than ignored.

Related: ADR-0011 (one component per third-party library) — the vendor
client still sits behind exactly one brick, now the adapter rather than
a shared component. ADR-0019 (processor packaging) — which adapter runs
where is the same deployment-time composition question.
