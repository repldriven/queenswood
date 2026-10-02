# Parties and identity verification

> **Status: proposal.** Parties, the IDV record, the activation chain
> and the IDV adapters exist, and Background names them. Everything
> under Proposed Solution is the build list; the criteria, the provider
> declaration, evidence and sessions are built for both adapters, and
> [First slices](#first-slices) says what comes next.

## Objective

Every account belongs to a **party**: a natural person, a non-person
legal entity, or an internal bookkeeping identity of the bank. A person
is verified before it can transact. This TDD decides how a bank's
policies state what a verification must establish, the contract every
IDV adapter meets whichever provider it speaks to, how a provider's
evidence becomes a decision, and how the person reaches the provider
from a tenant's web or mobile app.

In scope: the `party` and `idv` bricks; the `idv-action-accept`
capability and the verifications and screenings its denies name; the
provider declaration and the check that it meets a bank's policies;
the IDV adapter contract, covering evidence, sessions and the
simulator every adapter ships; verification sessions and their
hand-off to the person; name matching.

Out of scope: users and their authentication, distinct from parties,
see [authentication.md](authentication.md); the policy engine's
matching, bindings and limits, see
[policy-evaluation.md](policy-evaluation.md); tiers and bank creation,
see [banks.md](banks.md); account ownership, see
[cash-accounts.md](cash-accounts.md); Confirmation of Payee, see
[payments.md](payments.md); the demo bank's own onboarding screens, see
[demo-digital-bank.md](demo-digital-bank.md); how a particular
provider's API maps onto the contract, which lives with its adapter;
organisation (KYB) verification, which no adapter offers yet.

## Background

- **Party types.** `party` holds three types. A person starts
  `pending` and waits on verification; an organisation and an internal
  party start `active`. `party-query` holds the reads and
  `match-name`, which normalises and tokenises two names and answers
  `:match`, `:close-match` or `:no-match`.
- **Person identification.** `person-identification` holds a person's
  given, middle and family names, date of birth, nationality and one
  current address, the address snake-case with its country as ISO
  3166-1 alpha-3.
- **Lifecycle.** `suspend-party`, `resume-party` and `close-party` are
  direct single-phase commands guarded on source status in `party`'s
  `domain.clj`, per
  [lifecycle-transitions](../recipes/code/lifecycle-transitions.md).
  `merge-party` tombstones a duplicate with a pointer to the survivor.
- **The IDV record.** `idv` holds one IDV per person party, keyed
  `idv.<ulid>`, unique on party through `Idv_by_party`, with status
  `pending`, `in-review`, `accepted`, `rejected` or `failed`, each
  transition guarded on source status in `idv`'s `domain.clj`.
- **The activation chain.** A pending person party relays
  `party-status-changed` off the parties changelog. `idv`'s
  `party-event-processor` creates the IDV, which waits pending for a
  verification session. An IDV adapter turns the provider's webhook
  into an `idv-evidence` event on `idv-event`. `idv`'s
  `event-processor` moves the IDV, the
  idvs changelog relays
  `idv-status-changed` on `idvs-event`, and `party`'s
  `idv-event-processor` activates or rejects the party. Each hop
  crosses a durable channel per
  [ADR-0021](../adr/0021-changelog-relay.md).
- **IDV adapters.** Each is a base, `<provider>-adapter`, with a
  `<provider>-relay` component holding its `<provider>-outbox` and
  `<provider>-outbound-intents` stores and the runner that calls the
  provider outside any transaction, a `<provider>-webhook` component
  holding the provider's wire schemas, and a `<provider>-simulator`
  base standing in for the provider. The adapter consumes
  `submit-idv-check` into an intent, the runner starts the provider's
  run carrying the bank and verification ids for correlation, and the
  adapter serves the provider's webhook and writes what it reports to
  its outbox, relayed to `idv-event`. Two adapters exist, Zyphe's and
  Onfido's, and every build runs both side by side.
- **Simulators.** Each adapter's simulator waits for a decision, made
  on the hosted page `idv-simulator-page` serves for both or through
  its decision route. `test-scenarios` runs Zyphe's, and
  `test-api-scenarios` runs both.
- **The provider as a deployment fact.** Which adapter runs is decided
  by the service's `application.yml`, never by a request, per
  [ADR-0020](../adr/0020-providers-are-deployment-facts.md). Each
  adapter consumes its own command channel, `zyphe-idv-command` for
  Zyphe.
- **Capabilities.** `policy`'s `check-capability` takes a kind and a
  request map. A capability matches when its kind and its fields beside
  `filters` equal the request's, and when it has no filters or any one
  filter's set fields all equal the request's, an unset field matching
  anything (`match.clj`). Any matching deny refuses with its reason,
  otherwise any matching allow permits (`capability.clj`). The
  `tier=platform` policy applies to every bank, and the console renders
  each capability's effect, reason and filters. IDV has one action,
  `idv-action-submit`, and a daily count limit per tier.
- **What the tenant sees.** A tenant reads the party's status and,
  with the routes under [Verification sessions](#verification-sessions),
  the verification. The webhook catalogue lists `party.opened` and
  `party.rejected`.

## Proposed Solution

### Verifications and screenings

What a verification establishes falls into two kinds, each an enum in
`policy.proto`, so a policy names what the person must show, never a
provider's method:

- **`IdvVerification`**, established from something the person
  presents:
  - `identity` — name and date of birth, read from a genuine
    government photo document.
  - `liveness` — the person is live and matches the document's
    portrait.
  - `claimed-identity` — the document's name and date of birth match
    what the tenant registered for the party.
  - `address` — the residential address, from an address document.
- **`IdvScreening`**, established by checking lists:
  - `sanctions` — screened against sanctions lists.
  - `pep` — screened for being a politically exposed person.

### Criteria as capabilities

Accepting a verification becomes an action, `idv-action-accept`, and a
bank's criteria are denies on accepting while a verification is
unverified or a screening unscreened. `IdvCapability` gains `repeated
IdvCapabilityFilter filters`, whose fields are `party_type`,
`unverified` (an `IdvVerification`) and `unscreened` (an
`IdvScreening`). A filter sets one of the two, so an unscreened
address or an unverified PEP cannot be written. The platform policy's
`capabilities/idvs.yml`:

```yaml
- effect: !keyword effect-allow
  reason: "Allow accepting a verification"
  kind:
    idv:
      action: !keyword idv-action-accept
- effect: !keyword effect-deny
  reason: "A person's address must be verified"
  kind:
    idv:
      action: !keyword idv-action-accept
      filters:
        - party-type: !keyword party-type-person
          unverified: !keyword idv-verification-address
- effect: !keyword effect-deny
  reason: "A person must be screened for being politically exposed"
  kind:
    idv:
      action: !keyword idv-action-accept
      filters:
        - party-type: !keyword party-type-person
          unscreened: !keyword idv-screening-pep
```

`match.clj` and `capability.clj` do not change. The meaning of
`unverified` and `unscreened` lives in the request `idv` builds: one
check per verification or screening not yet established, carrying it
in its field, and one check carrying neither once everything is. A deny
matches only the check that names its value, so its reason is the
answer while that one is outstanding. A value no deny names passes, so
its absence never holds a verification up.

- **Platform.** One deny per verification and screening, six in all.
  That is the customer due diligence a UK bank owes a person onboarded
  remotely: name, date of birth and address verified, the person live
  and matching the document, and screened.
- **Micro.** Nothing added. A micro bank may be live, so it takes the
  floor as it is. Deny wins, so no tier lowers the floor, and a tier
  for riskier products raises it with a deny of its own.

`idv/unmet-criteria policies declaration` probes every value of both
enums against `policies`, through `idv-query`, which holds the IDV's
reads and the probe, and returns those a deny requires that the
declaration below lacks. The change adds a field to the `Policy`
record's nested capability, which bumps the meta-data version per
[schema-evolution](../recipes/code/schema-evolution.md).

### The provider declaration

`system/idv-providers/<key>.yml` declares what a provider's adapter
can establish, the hand-offs it offers, and what it needs as input,
`zyphe.yml` for Zyphe:

```yaml
!system/component
system/component-kind: idv-provider/declaration
verifies: [identity, liveness, claimed-identity, address]
screens: [sanctions, pep]
channels: [web, mobile]
hand-offs: [url]
needs: [email]
```

A system's `idv-provider` group includes each provider's file under its
key, and its `idv-provider/providers` component names the default and
each provider's command channel, as
[bank-providers.md](bank-providers.md) describes. An adapter refers to
its own declaration, `idv-provider.<key>`, and every other component
that reads one takes it through `idv-provider.providers`, so a bank
changes its criteria rather than a request changing the provider:

- **At start-up.** A new component kind, `idv/criteria-check`, takes
  the seeded platform policy and the providers and fails to start
  while `unmet-criteria` is not empty for any provider offered.
  `bootstrap` and `monolith` include it, so a floor a provider cannot
  meet stops the bootstrap.
- **At bank creation and tier change.** `bank`'s processor takes the
  default provider's declaration, and `new-bank` and `change-bank-tier`
  call `idv/check-criteria` on the platform and tier policies, which
  rejects `:idv/unsupported-criteria` (422) naming what is missing.

### The evidence contract

The adapter reports evidence, and `idv` decides. An Avro event
`idv-evidence` on `idv-event`, registered in both YAMLs, carries
`bank-id` and `verification-id`, and one optional section per kind of
evidence the provider event reported:

- **`document`** — `passed`, `failed` or `review`, with the extracted
  `given-names`, `family-name`, `date-of-birth`, `document-type` and
  `issuing-country`.
- **`liveness`** — `passed` or `failed`.
- **`address`** — `passed` or `failed`, with the `document-type`.
- **`screening`** — `sanctions` `clear`, `possible-match` or `hit`, and
  `pep` `true` or `false`.
- **`cancelled`** — `true` when the person abandoned the run.

`Idv` gains an `evidence` sub-message holding the latest of each kind,
and `criteria`, each verification and screening with its state:
`outstanding`, `established`, `review` or `failed`. `idv`'s
`event-processor` handles `idv-evidence` by merging it and calling
`domain/decide idv policies claimed`. `claimed` is the person
identification, read through `person-identification`, and the
claimed-identity comparison uses `party-query/match-name` on the names
and equality on the date of birth. `decide` settles each verification
and screening by one treatment table:

| Verification or screening | Established | Review | Reject |
|---|---|---|---|
| identity | document passed | document review | document failed |
| liveness | liveness passed | — | liveness failed |
| claimed-identity | `:match`, same date of birth | `:close-match` | `:no-match` or another date of birth |
| address | address passed | — | address failed |
| sanctions | clear | possible match | hit |
| pep | not a PEP | a PEP | — |

Any reject makes the IDV `rejected`; otherwise any review makes it
`in-review`; otherwise it is `accepted` when the `idv-action-accept`
checks all pass, and stays `pending` while one is denied. A PEP is
enhanced due diligence, not a refusal, so it reviews. `cancelled`
fails the IDV. A claimed identity with no family name stays
outstanding. Redelivered evidence merges to the same record and
decides the same way, so it needs no dedup beyond the outbox's, and an
IDV no longer pending or in review takes no more.

### Verification sessions

The person reaches the provider through a session the tenant opens, so
the channel, the return URL and the email are known when the run
starts. The party route gains:

- **`POST /v1/parties/{party-id}/verification-sessions`** —
  `{channel return-url email}`, where `channel` is `web` or `mobile`
  and `return-url` is an https page, an http page on the loopback
  interface for local development, or an app link. Returns 202 with a
  `Location` and the session `opening`. Rejects
  `:idv/invalid-status` (409) unless the IDV is pending,
  `:idv/unsupported-channel` (422) for a channel the provider does not
  declare, and `:idv/missing-email` (422) where the provider needs one.
- **`GET /v1/parties/{party-id}/verification-sessions/{session-id}`** —
  the session, its `status` one of `opening`, `ready`, `expired`,
  `completed` and `failed`, with `hand-off` `{type url expires-at}`
  while ready and `failure-reason` once failed.
  Rejects `:idv/session-not-found` (404).
- **`GET /v1/parties/{party-id}/verification`** — the IDV's status,
  and each verification and screening as outstanding, established, in
  review or failed, with the reasons of the denies still outstanding,
  never the extracted identity.

The session is a command, `open-idv-session` on `idvs-command`, to
`idv`'s processor, which checks `idv-action-submit` and its daily limit
in one transaction, saves the session to the `idv-sessions` store, and
once that commits publishes `submit-idv-check` with the session, the
channel, the return URL, the email, and the verifications and
screenings the bank's denies require. The adapter reports the hand-off
as an `idv-session-opened` event
`{bank-id verification-id session-id url expires-at}`, which makes the
session `ready`. A check the provider refuses, or the adapter gives up
on, it reports as `idv-session-failed`
`{bank-id verification-id session-id reason}`, which makes an opening
or ready session `failed` with the reason; the IDV and the party stay
pending, and another session may open. A session reads `expired` once
`expires-at` passes, and is `completed` when the IDV leaves pending.
The hand-off is never logged. The `idv-sessions` changelog relays
`idv-session-status-changed` on `idvs-event`, and the webhook catalogue
sends `party.verification-session-ready` and
`party.verification-session-failed` with the session as the read route
returns it. Party creation no longer publishes
`submit-idv-check`: the IDV waits, pending, for the first session.
Opening a session for a pending person whose IDV the party event has
not created yet creates it in the session's transaction, so a session
opened straight after the party is not refused.

### The IDV adapter contract

Every IDV adapter, `<provider>-adapter` with its relay, webhook and
simulator bricks, meets the same contract, so which one a deployment
runs changes nothing outside it:

- **Declares.** It ships its provider's declaration,
  `system/idv-providers/<key>.yml`, verifying `claimed-identity` only
  where the provider returns the document's extracted name and date of
  birth. Its config maps
  provider configurations to the verifications and screenings each
  establishes, and it refuses to start when they do not cover what the
  file declares.
- **Starts or resumes a run.** It consumes `submit-idv-check` into an
  intent, and its runner starts the provider's run on the smallest
  configuration covering the verifications and screenings requested.
  Starting again for the same verification resumes the run rather
  than opening a second one, and mints a fresh hand-off.
- **Hands off.** It builds the hand-off for the session's channel,
  carrying the return URL so the person comes back to the tenant, and
  reports it as `idv-session-opened`.
- **Reports a failure.** A run the provider refuses, or one still
  failing after the relay's attempts, it reports as
  `idv-session-failed` with the reason.
- **Reports evidence.** It authenticates each delivery from the
  provider, as the provider signs it, before anything else, maps each
  provider result to `idv-evidence`, and writes one outbox entry per
  provider event, deduplicated on the provider's event id.
- **Stays neutral.** Its anomalies are the `:idv/*` kinds, its
  correlation carries only opaque ids, and no provider name leaves its
  bricks.
- **Ships a simulator.** `<provider>-simulator` serves the provider's
  API as the adapter calls it, signs its deliveries as the provider
  does, and serves the hosted page the hand-off points at. The page
  asks for what the person's document says and offers the outcomes a
  person or a reviewer produces: a document that matches, someone
  else's document, a document in review, a forged document, failed
  liveness, a failed address document, a sanctions hit, a sanctions
  possible match, a PEP, and walking away.
  Submitting emits the provider's results for that outcome and returns
  the person to the return URL. A decision route takes the same body,
  so a test drives what a person would, and nothing settles a run
  without one. A check for `refused@verification.example` is refused.
  Deployed, the console proxies the page at
  `/identity-provider/`, and the adapter's verify URL points there.

### First slices

1. **Criteria.** `IdvVerification`, `IdvScreening`,
   `idv-action-accept` and its filter, the platform denies,
   `idv-provider.yml`, `idv/unmet-criteria`, `idv/criteria-check`, and
   the bank checks. Proved by a criteria check refused against a
   declaration that does not verify `address`, and a bank create and a
   tier change refused for a tier requiring what the declaration
   lacks. Built.
2. **Evidence.** `idv-evidence`, the IDV's evidence, `domain/decide`,
   and the deployed adapter and its simulator reporting evidence. The
   scenario rigs move to that simulator and drive its decision route,
   and the name-based rejection retires from them. Proved by a scenario
   per row of the treatment table. Built.
3. **Sessions.** The routes, `open-idv-session`, the hand-off, the
   hosted page, and party creation no longer submitting. The console's
   onboarding scenario opens a session and sends Zaphod through the
   hosted page with a sanctions hit, and the demo bank's sign-up hands
   the person to it. Proved by API scenarios for the hand-off and the
   refusals. Built, with slice 2, so no build has persons who cannot
   activate.
4. **The second adapter.** Onfido's adapter to the contract: its
   declaration, a Studio workflow run started or resumed by its tags,
   the run's link as the hand-off, evidence read back from the run's
   reports once a signed `workflow_run.completed` arrives, and its
   simulator on the shared hosted page. `idv-completed` retires.
   Proved by the party scenarios on Onfido. Built.

Resolving a review and re-verification follow under this TDD.
The demo bank's onboarding screens follow under
[demo-digital-bank.md](demo-digital-bank.md).

### Tests

- **`policy`** — an `unverified` or `unscreened` deny matching only
  the check that names its value, and passing a check that names
  neither.
- **`idv`** — `unmet-criteria` over the platform and micro policies,
  `domain/decide` over every row of the treatment table, evidence
  merged in any order, redelivery deciding the same way, the
  session's refusals, and a session made ready, completed and failed.
- **`idv-query`** — the criteria a bank's policies require, and a
  session reading expired.
- **`bank`** — create and tier change refused with what is missing.
- **`<provider>-adapter`** — each provider result mapped to its
  evidence, an unauthenticated delivery refused, and the refusal to
  start on a declaration its configuration does not cover.
- **`<provider>-relay`** — configuration selection by verifications and
  screenings, resuming a run, and the hand-off for each channel.
- **`<provider>-simulator`** — each hosted-page outcome posting its
  results, authenticated as the provider's are.
- **`test-api-scenarios`** — a scenario per row of the treatment
  table, a session handing off, the refusals, a session refused once
  the IDV decides, a session the provider refuses, and the
  verification read.
- **`test-scenarios`** — every person verified through the simulator
  with a matching document.

## Alternatives Considered

- **A third rule kind, `requirements`, beside capabilities and
  limits.** Rejected: it reads directly, but it is one more thing to
  evaluate, store and render, where deny precedence already gives a
  floor no tier can lower.
- **One `missing` field for every verification and screening.**
  Rejected: separate `unverified` and `unscreened` fields read as what
  each is, and make a mismatched pair unwritable.
- **Criteria in the adapter's config.** Rejected: a bank's criteria
  would live beside a vendor's credentials, invisible to anyone reading
  the bank's policies.
- **Negotiating criteria per request.** Rejected: a request would then
  choose what a verification establishes, which ADR-0020 keeps to
  deployment.
- **Treatments as policy data.** Rejected for now: one table serves
  every bank, and it moves into policy when a bank needs another.
- **The provider's overall outcome decides.** Rejected: it hides which
  verifications and screenings were established, and leaves the
  claimed-identity match to whatever the provider happens to compare.
- **A native-SDK token as the hand-off.** Taken in part: `hand-offs`
  admits another type, but a URL serves web and a mobile WebView for
  any provider with a hosted flow, so it is the one every adapter
  offers.
- **Minting the hand-off on every read.** Rejected: it puts a provider
  call in a read's path, where the relay keeps every provider call
  outside a request.
- **Starting the run at party creation.** Rejected: the channel, the
  return URL and the email are unknown until the tenant has the person
  in front of it.
- **Direct calls between `party`, `idv` and the adapter.** Rejected:
  it couples the bricks and loses each hop's durable, replayable
  event.
- **A saga orchestrating the chain.** Rejected: relayed events and bus
  subscribers carry a chain this short.
- **One brick for parties and IDV.** Rejected: identity and its
  verification change for different reasons.
- **A person-only party model.** Rejected: bookkeeping needs internal
  parties and counterparties need organisations, and one model with a
  type serves all three.

## Known Limitations

- **The denies read backwards.** A criterion is written as a refusal
  while something is outstanding, and why `unverified` and
  `unscreened` work lives in `idv`'s requests, not in the policy.
- **The tier check refuses nothing yet.** The platform denies name
  every verification and screening, so a declaration that passes the
  start-up check meets every tier, until a value joins the enums that
  the platform floor does not name.
- **Reviews have no resolution.** A PEP or a possible sanctions match
  leaves the IDV `in-review`, and nothing lets an operator accept or
  reject it.
- **An Onfido hand-off is the run's own link.** Onfido mints no fresh
  link for a run, so a session resuming one hands the person the link
  it was created with, and a run whose link has expired is left and
  another started.
- **Party webhooks are not delivered.** The catalogue lists the
  `party.*` kinds, but no webhook consumer reads `parties-event`, so a
  tenant learns a party opened or was rejected only by reading it.
- **The verification read can lag the party.** Until the party event
  or a session creates the IDV, `GET .../verification` returns 404.
- **The simulator's page is reached through the console.** A deployed
  instance without the console has no route to the hosted page.
- **`claimed-identity` depends on the provider returning extracted
  details.** A provider that returns only a pass or a fail cannot
  verify it, whatever its document check does.
- **No re-verification.** An active person is never verified again,
  and screening is not repeated.
- **No organisation verification.** An organisation party starts
  active, and no adapter verifies a company or its owners.
- **No organisation party over the API.** The create route takes person
  parties only, so the PRD's organisation journey cannot run.
- **The hand-off URL is a credential at rest.** It is stored unencrypted
  until it expires, as is all PII in FDB.
- **Party and User are not linked.** A `User` and a `Party` coexist
  with no relation between them.
- **Name matching is naive.** Token sets after lower-casing, with no
  accent folding, transliteration or edit distance.
- **National identifiers are not validated per type.**
- **Merging does not re-parent.** IDVs, identifiers and person
  identification stay on the merged-away party.

## References

- [parties](../prd/parties.md) — the product requirements this design
  serves.
- [policy-evaluation](policy-evaluation.md) — capability matching,
  deny precedence, bindings and tiers, which the criteria reuse.
- [banks](banks.md) — bank creation and tier change, where the
  agreement is checked.
- [payments](payments.md) — Confirmation of Payee, the other caller of
  `match-name`.
- [transaction-processing](transaction-processing.md) — the intent and
  outbox pattern every IDV adapter follows.
- [ADR-0020](../adr/0020-providers-are-deployment-facts.md) — the
  provider as a deployment fact.
- [ADR-0021](../adr/0021-changelog-relay.md) — the changelog relay the
  activation chain runs on.
- [schema-evolution](../recipes/code/schema-evolution.md) — the
  meta-data bumps for the `Policy` and `Idv` changes and the
  `idv-sessions` store.
