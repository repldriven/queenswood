# Parties and identity verification

## Objective

Every account in Queenswood belongs to a **party** —
either a natural person, a non-person legal entity, or an
internal bookkeeping identity for the bank itself. Persons
are subject to identity verification (IDV) before they can
transact; non-person and internal parties become active
immediately. IDV runs through a real provider integration
(or a simulator standing in for one): a person party's
creation publishes a `submit-idv-check` command, the
adapter calls the provider, and a webhook-borne
`idv-completed` event flips the IDV record. This TDD
describes the party model, the three types, the activation
flow that traverses FDB changelogs and the message bus to
the IDV adapter, and the honest gaps in today's IDV
machinery.

In scope: the `party` and `idv` bricks; the
`onfido-adapter` and `onfido-simulator` bases;
party types and lifecycle; the relay + bus + event flow
that drives person-party activation; name-matching; party
identifiers and person identifications.

Out of scope: the HTTP-edge auth model and user identity —
see [authentication.md](authentication.md), with parties distinct from
users; see Background; cash account ownership and SCAN
assignment, see [cash-accounts.md](cash-accounts.md);
Confirmation of Payee callers, covered in
[payments.md](payments.md).

## Background

A **party** is a participant the bank tracks: the *who* on
both sides of every transaction. The model has three types,
each with different lifecycle rules.

- **Person parties** — natural humans. KYC requires verified
  identity before they can hold a cash account or transact.
  Status starts `pending`; flips to `active` when IDV
  accepts.
- **Organisation parties** — non-person legal entities (a
  customer's customer that's a company, for example). No
  per-person KYC; status starts `active`.
- **Internal parties** — Queenswood's own bookkeeping
  identities (settlement, fee P&L, suspense accounts, and so
  on). The bank's books. Status starts `active`.

A note on terminology that often confuses: **a party is not
a user**. A `User` is the authenticated human, an OIDC identity
described in [authentication.md](authentication.md), and is
deliberately separate from a party. A party is the
customer-of-the-customer or counterparty the bank deals with
as a *customer of the bank's customer*; a user is the
authenticated human triggering a request. They serve
different concerns, and there is no link between them yet.

For person parties, KYC sits between creation and activation.
The system implements this with the changelog relay per
[ADR-0021](../adr/0021-changelog-relay.md) at the
boundaries and the message bus per
[ADR-0003](../adr/0003-message-bus-abstraction.md)
throughout: a party write is relayed as a
`party-status-changed` event that triggers an IDV write, the
IDV write
publishes a `submit-idv-check` command, the IDV-provider
adapter calls the provider and republishes the eventual
webhook as an `idv-completed` event, the IDV event
processor flips the IDV record, and the IDV flip triggers
party activation. The flow is decoupled end-to-end — no
direct call from `party` to `idv` to the
adapter; each hop crosses a durable channel.

## Proposed Solution

### Architecture

Two bricks plus an adapter/simulator base pair:

- **`party`** — owns Party records, party CRUD, name
  matching, party identifiers (passport, NI), person
  identifications (given/family/middle names), and the
  `idv-status-changed` handler that activates parties on
  IDV acceptance.
- **`idv`** — owns IDV records, the
  `party-status-changed` handler that creates IDVs from
  pending parties, the `initiate` core
  fn that publishes `submit-idv-check`, and the
  `IdvEventProcessor` that consumes `idv-completed` events
  and flips the IDV record.
- **`onfido-adapter`** (base) — talks to the
  IDV provider over HTTP. Subscribes to `submit-idv-check`
  on the bus, calls Onfido's `POST /v3.6/applicants` and
  `POST /v3.6/checks`, receives `check.completed`
  webhooks, and republishes them as `idv-completed`
  events on the bus.
- **`onfido-simulator`** (base) — Onfido-shaped HTTP
  service used for development and tests. Mocks the
  applicant + check + webhook lifecycle deterministically.

`party` and `idv` communicate over the bus, via events
relayed off each other's changelogs per
[ADR-0021](../adr/0021-changelog-relay.md).
`idv` and `onfido-adapter` communicate via the
message bus per
[ADR-0003](../adr/0003-message-bus-abstraction.md) — a
command channel for `submit-idv-check` and an event
channel for `idv-completed`.

```mermaid
graph TD
    HTTP[HTTP create-person-party]
    PARTY["party<br/>(party-status-pending)"]
    PCH[("parties changelog")]
    RELAY1["changelog relay"]
    BUS[("message-bus")]
    IDV1["idv<br/>IdvPartyEventProcessor<br/>creates IDV (pending)<br/>+ publishes submit-idv-check"]
    IDV[("IDV record")]
    ADAPTER["onfido-adapter"]
    ONFIDO["Onfido<br/>(or simulator)"]
    EP["idv<br/>IdvEventProcessor"]
    ICH[("idvs changelog")]
    RELAY2["changelog relay"]
    PARTY3["party<br/>PartyIdvEventProcessor<br/>activates party"]
    PARTY4["Party (active)"]

    HTTP -->|new-party| PARTY
    PARTY --> PCH
    PCH --> RELAY1
    RELAY1 -->|party-status-changed| BUS
    BUS -->|consume| IDV1
    IDV1 --> IDV
    IDV1 -->|submit-idv-check| BUS
    BUS -->|consume| ADAPTER
    ADAPTER -->|POST /v3.6/applicants<br/>POST /v3.6/checks| ONFIDO
    ONFIDO -.->|check.completed<br/>webhook| ADAPTER
    ADAPTER -->|idv-completed| BUS
    BUS -->|consume| EP
    EP --> IDV
    IDV --> ICH
    ICH --> RELAY2
    RELAY2 -->|idv-status-changed| BUS
    BUS -->|consume| PARTY3
    PARTY3 --> PARTY4
```

Each hop is independently observable and testable: the
`party-status-changed` event off the parties changelog, the
`submit-idv-check` command on the bus, the adapter's HTTP
call, the webhook receipt, the `idv-completed` event, the
event processor's flip, and the `idv-status-changed` event
off the idvs changelog that activates the party.

### Data model

**Party**:

```clojure
{:organization-id
 :party-id        "pty.<ulid>"
 :type            :party-type-person
                  ;; or -organization, -internal
 :display-name
 :status          :party-status-pending
                  ;; or -active, ...
 :created-at
 :updated-at}
```

**PartyNationalIdentifier** — one per identifier type per
party:

```clojure
{:organization-id
 :party-id
 :type            ;; :passport, :ni, etc.
 :value
 :issuing-country
 :created-at}
```

**PersonIdentification** — names, demographics, and residence
for person parties:

```clojure
{:party-id
 :given-name
 :middle-names                    ;; optional
 :family-name
 :date-of-birth                   ;; YYYYMMDD int
 :nationality                     ;; ISO 3166-1 alpha-2
 :address
 {:flat-number                    ;; optional
  :building-number                ;; optional
  :building-name                  ;; optional
  :street                         ;; required
  :sub-street                     ;; optional
  :town                           ;; required
  :state                          ;; optional (US: USPS abbrev)
  :postcode                       ;; required
  :country                        ;; required, ISO 3166-1 alpha-3
  :start-date}                    ;; optional, YYYY-MM-DD
 :created-at}
```

The address shape mirrors the Entrust/Onfido applicant address
object so the adapter can forward it without a code-table
translation. Two deliberate asymmetries:

- **Nationality stays alpha-2 (`GB`); address country goes
  alpha-3 (`GBR`).** Onfido's applicant accepts alpha-3 in
  `address.country`; we mirror that exactly. Nationality is a
  separate concept and the proto's `nationality` predates the
  Onfido alignment.
- **Middle names live as a separate field but are concatenated
  into `first_name` at the adapter edge.** Onfido has no
  `middle_name` field; the standard pattern is
  `first_name = "Arthur Phillip"`. Storing them separately keeps
  the data legible internally.

Single current address only — previous-N-years lookback (Onfido
supports it via `POST /applicants/:id/addresses`) is not modelled
today and is a future follow-up if a customer relationship
requires it.

**IDV** — the verification record itself:

```clojure
{:organization-id
 :verification-id
 :party-id
 :status        :idv-status-pending
                ;; or -accepted, -rejected
 :created-at
 :updated-at}
```

### Party types and initial status

```clojure
:party-type-person       → :party-status-pending
:party-type-organization → :party-status-active
:party-type-internal     → :party-status-active
```

The split is intentional. Person parties carry the KYC
obligation; orgs and internal don't. The bank's own
bookkeeping (internal) and the customer's non-person
counterparties (organization) don't need IDV before they can
appear in transactions.

### Lifecycle transitions

Beyond the IDV-driven pending → active path, a party has
three tenant-driven transitions. Each is direct and
single-phase: a command over the bus, one FDB transaction,
no reactive second leg off the changelog.

- `suspend-party` — `:party-status-active` →
  `:party-status-suspended`
- `resume-party` — `:party-status-suspended` →
  `:party-status-active`
- `close-party` — `:party-status-active` or
  `:party-status-suspended` → `:party-status-closed`

`domain.clj` guards each transition's source state as the
first binding of its `let-nom>`, ahead of the capability
check, and rejects `:party/invalid-status` (HTTP 409)
carrying the offending status and the set the transition
accepts. See
[lifecycle-transitions](../recipes/code/lifecycle-transitions.md).

Closing also reads the party's cash accounts through
`bank-cash-account-query` and rejects `:party/open-accounts`
while any of them is not closed — the same check
`merge-party` makes of the party being merged away.

Closed is terminal, so the resume guard admits
`:party-status-suspended` only and is not a way back from
closure. Suspension and closure are a separate axis from the
pending → active → rejected path IDV drives: a pending or
rejected party is neither suspendable nor closeable.

Each transition carries its own capability —
`:party-action-suspend`, `:party-action-resume`,
`:party-action-close` — checked against the effective
policies inside the same transaction as the write.

### The activation flow

The pending → active transition for a person party
crosses two relayed changelog events, one bus command, one
HTTP round-trip to the IDV provider, and one bus event.

```mermaid
sequenceDiagram
    participant H as HTTP handler
    participant P as party
    participant R as changelog relay
    participant B as message-bus
    participant W1 as idv<br/>IdvPartyEventProcessor
    participant I as idv
    participant A as onfido-adapter
    participant O as Onfido<br/>(or simulator)
    participant E as idv<br/>IdvEventProcessor
    participant W2 as party<br/>PartyIdvEventProcessor

    H->>P: new-party (type=person)
    P->>P: write Party + changelog entry (one Tx)
    Note over P: parties changelog fires

    R->>P: tail parties cursor
    R->>B: publish party-status-changed
    B->>W1: consume (status-after=pending)
    W1->>I: core/initiate-for-party
    I->>I: write IDV (status=pending)
    I->>B: publish submit-idv-check

    Note over B,O: Asynchronous from here

    B->>A: consume submit-idv-check
    A->>O: POST /v3.6/applicants
    A->>O: POST /v3.6/checks (external_id=org-id|verification-id)
    O-->>A: 2xx
    O-->>A: webhook check.completed
    A->>B: publish idv-completed

    B->>E: consume idv-completed
    E->>I: update IDV + changelog entry (one Tx)
    Note over I: idvs changelog fires

    R->>I: tail idvs cursor
    R->>B: publish idv-status-changed
    B->>W2: consume (status-after=accepted)
    W2->>P: get-party
    W2->>P: update Party (status=active)
```

Each handler is idempotent on the matching status — running
twice doesn't double-initiate or double-activate, which is
what makes at-least-once delivery off the relay safe. The IDV
handler additionally consults the
unique `Idv_by_party` index before initiating, so a
changelog replay or a duplicate party-pending event won't
create a second IDV.

### Onfido adapter

`onfido-adapter` is its own base. It owns:

- **Command consumer** — message-bus consumer for
  `submit-idv-check` commands. For each, calls Onfido's
  `POST /v3.6/applicants` (mapping the IDV's first-name /
  last-name / date-of-birth) and `POST /v3.6/checks`,
  smuggling the originating
  `:organization-id|:verification-id` into the check's
  `external_id` field as a correlation channel.
- **Webhook receiver** — HTTP endpoint
  `POST /webhooks/onfido/check-completed` under its own
  server (separate from `api`). Parses the Onfido
  payload, parses the composite `external_id` back into
  org-id / verification-id, and republishes as an
  `idv-completed` event with `:status` set to `ACCEPTED`
  (Onfido `clear`) or `REJECTED` (Onfido `consider`). It
  authenticates nobody: the route carries no security
  metadata, the chain ahead of it only injects
  components, and no adapter config holds a signing
  secret. Verifying Onfido's signature is an open gap.
- **Periodic webhook re-register daemon** — re-asserts
  the adapter's webhook registration with the provider
  on a schedule. Closes the silent-loss window when the
  simulator (or provider) restarts and forgets registered
  webhooks.

The adapter is the only Queenswood code that talks HTTP to
Onfido. The rest of the system sees only bus messages.

### Onfido simulator

`onfido-simulator` is its own base, deployed in
development and tests. It exposes the subset of Onfido's
HTTP API that the adapter uses:

- **`POST /v3.6/applicants`**, **`GET /v3.6/applicants/{id}`**.
- **`POST /v3.6/checks`** — async. Records the check, then
  fires a `check.completed` webhook after a configurable
  delay.
- **`GET /v3.6/checks/{id}`**.
- **`POST/GET/DELETE /v3.6/webhooks`** — registration CRUD;
  `POST` deduplicates by URL so adapter bounces don't
  accumulate duplicate registrations.

Outcome routing is deterministic and keyed off the
applicant's `first_name`:

- `Reject` (case-sensitive) → Onfido `consider` → maps to
  `REJECTED` at the adapter.
- Default → `clear` → maps to `ACCEPTED`.

The `external_id` field on the create-check request flows
through to the webhook payload as a correlation channel —
a simulator-only extension to the Onfido shape, used in
tests but transparent to production-Onfido callers.

The simulator is approximate (happy-path applicant + check
roundtrip; deterministic outcomes; no rate limiting; no
real document upload pipeline) but covers the choreography
end-to-end so tests can exercise the full activation loop
without external calls.

### Name matching

`match-name` compares two name strings and returns one of:

- **`:match`** — exact equality after lower-casing,
  whitespace normalisation.
- **`:close-match`** — every token in the shorter name
  appears in the longer (handles middle-names, abbreviations
  in either direction).
- **`:no-match`** — neither.

Used by Confirmation of Payee flows (in the
ClearBank adapter; see payments TDD) and elsewhere when the
caller needs a soft equality on display names.

The implementation is a deliberately simple normalise-and-
tokenise pass — it covers the bulk of real cases without a
fuzzy-matching dependency. See Known Limitations for the
edge cases it doesn't cover.

### Why the changelog relay + bus (and not direct calls)

The party → IDV → provider → party-active flow could
equally be written as direct procedural calls inside the
create-party handler: write the party, write the IDV, call
the provider over HTTP in-band, wait, flip the party.
Choosing the relay + bus pattern is deliberate — see
[ADR-0003](../adr/0003-message-bus-abstraction.md) and
[ADR-0021](../adr/0021-changelog-relay.md).

Reasons:

- **Decoupling.** `party` doesn't import `idv`
  and vice versa; `idv` doesn't import the adapter;
  the adapter doesn't import `idv`. Each brick or
  base evolves independently.
- **Observability.** Each transition is its own durable
  event — a changelog entry or a bus message — visible to
  tracing and replayable. Debugging "where did this party
  get stuck" is a question of "which step has no successor
  yet?".
- **Decoupled from provider latency.** Onfido checks can
  take seconds to minutes, or human review hours to days.
  The bus + webhook shape lets the HTTP path return
  immediately with a pending party; the adapter's
  command-consume / HTTP / webhook / event-publish loop
  finishes whenever the provider does.
- **Testability.** Each handler takes a config and a
  deserialised event, and returns a value or anomaly.
  Unit-testable without booting the full system.
- **Idempotency by status and unique index.** The IDV
  handler consults `Idv_by_party` before initiating; each
  handler short-circuits unless the status is the one it
  cares about; redelivering an event doesn't re-execute the
  transition.

The trade-off is that the chain is harder to follow if you
don't already know the model, and that ordering between
hops holds only because every topic is single-partition
today — see [ADR-0021](../adr/0021-changelog-relay.md).
Both costs are accepted.

## Alternatives Considered

- **Direct procedural calls between bricks.** Create-party
  calls IDV-create directly; IDV-create calls the adapter
  directly. Rejected — couples bricks; the
  observability story disappears; testability
  weakens. Relay + bus preserve the brick boundaries.
- **Single brick covering parties and IDV.** Coarser; loses
  the testability split; conflates KYC with party identity.
  Rejected — the two are conceptually separate even if
  always-paired in this product.
- **Synchronous IDV during create-party.** The HTTP handler
  blocks on the IDV provider's response, returns an active
  party (or error). Rejected for two reasons: real IDV
  providers can take seconds to minutes (or human review for
  hours/days); blocking the HTTP handler is a poor caller
  experience. Bus + webhook with a status-poll/read model
  is the right shape.
- **Auto-flipping IDV on receipt.** The previous
  iteration of `idv` unconditionally flipped pending IDVs
  to accepted, with no provider involved.
  Replaced — left no place for a real provider to plug in,
  and the flip-without-evidence pattern was never going to
  survive contact with a compliance review.
- **Direct adapter dependency in `idv`.** Have
  `idv` call Onfido's HTTP API directly. Rejected —
  couples the IDV brick to a vendor's API. The adapter
  base is the only place that knows about Onfido's wire
  shape; the rest of the system sees bus messages.
- **Saga / orchestrator.** A central orchestrator
  coordinates the steps. Rejected — overkill for a chain
  that relayed events and bus subscribers handle
  naturally.
- **Person-only party model.** Just persons; orgs and
  internal modelled differently. Rejected — bookkeeping
  needs a unified party concept (settlement *parties*, fee
  *parties*); collapsing them into one model with type
  discrimination is cleaner than three parallel models.

## Known Limitations

- **Production Onfido integration isn't deployed.**
  `onfido-adapter` speaks Onfido's HTTP API and is
  wired against `onfido-simulator` for development
  and tests. Pointing it at production Onfido needs real
  credentials, the production webhook URL, signature
  verification keys, and operator playbooks — none of
  which are deployed today. The architecture is
  pluggable; the production deployment isn't yet there.
- **Simulator outcomes are deterministic, not realistic.**
  `onfido-simulator` routes outcomes off the
  applicant's `first_name` (`Reject` → `consider`,
  default → `clear`). It doesn't model partial outcomes,
  manual-review queues, document-quality failures, or
  rate limits. Useful for end-to-end tests; not a stand-
  in for production behaviour.
- **IDV outcomes beyond accept and reject aren't acted
  on.** The `IdvStatus` enum already admits `IN_REVIEW` and
  `FAILED` (`idv.proto`), but `party/core.clj`'s
  `apply-idv-status` only maps accepted and rejected to a
  status transition; manual-review and
  technical-failure outcomes leave the party pending with no
  follow-up.
- **No re-verification flow.** Once a person party is
  active, there's no machinery to re-IDV them (periodic
  refresh, sanctions list re-screening, address change
  triggering re-verification). Compliance regimes
  increasingly require this; today the model assumes one-
  shot KYC.
- **Party and User are not linked.** A platform `User`
  (`user`, an OIDC identity) and `Membership`
  (`membership`, User→Bank) now exist and are
  deliberately separate from `Party` (the banking-domain
  customer). But there is still no relation tying a `User`
  to a `Party` — no *acts on behalf of* or *is a* link for
  self-service flows. The two identity models coexist
  without a join.
- **Name matching is naive.** Token-set matching after
  lower-casing. No accent folding, no transliteration, no
  edit-distance fuzziness, no honorific stripping. Real
  Confirmation-of-Payee scoring is harder than this brick
  admits and tends to need vendor-grade matching libraries.
- **National identifier types are uninterpreted.** The
  brick stores type/value/issuing-country but doesn't
  validate format per type — a "passport" record could
  contain anything. Caller-side discipline.
- **PII at rest is unencrypted.** Personal names, identifier
  values, and demographics live in FDB without field-level
  encryption. Production would want either tokenised
  storage or per-field encryption, depending on the
  regulator's view.
- **Merging is a tombstone plus a pointer, not a rewrite.**
  `merge-party` flips a suspended duplicate to
  `:party-status-merged` and sets `merged-into-party-id` at
  the survivor, and stops there. IDVs, national identifiers
  and person identification stay keyed to the merged-away
  party-id, so a reader wanting the whole picture follows
  the pointer rather than reading the survivor alone. There
  is no unmerge, and no re-parenting of the linked records.

## References

- [ADR-0002](../adr/0002-foundationdb-record-layer.md) —
  FoundationDB Record Layer (party storage)
- [ADR-0003](../adr/0003-message-bus-abstraction.md) —
  Message-bus abstraction (the IDV-provider channel and
  IDV event channel)
- [ADR-0021](../adr/0021-changelog-relay.md) — the
  changelog relay (the activation chain endpoints)
- [authentication.md](authentication.md) — Authentication (the `User`
  identity, distinct from parties)
- [payments.md](payments.md) — Payments (CoP consumes
  `match-name`; the same adapter/simulator pattern lives
  there for ClearBank)
- `party` brick interface
- `idv` brick interface
- `onfido-adapter` base
- `onfido-simulator` base
- `onfido-webhook` component (Malli schemas for the
  webhook envelope)
