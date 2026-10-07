# Plan: a person's identity evidence stays with the provider

Implements
[ADR-0045](../adr/0045-a-persons-identity-evidence-stays-with-the-customers-provider.md)
through slices 5 to 7 of [parties](../tdd/parties.md#first-slices).
Read the TDD's "What the platform keeps about a person", "The
simulator's hosted page" and "The IDV adapter contract" before
anything else; this plan says which files, in what order, and how each
step is proved. Docs and code move together: each slice ends by
marking itself built in the TDD, in the same pull request.

Branch: `pii-no-pii`, cut off `main` after #844. Worktree: `adrock`.

## What this delivers

- A person party registered with names and an optional
  `external-reference`, and nothing else that identifies them.
- The name read off a document compared in the adapter and reported as
  a grade, so no outbox entry, topic, record, log line or span carries
  what a provider read.
- A simulator page that plays the provider's flow — details, document,
  selfie, address — settled by sandbox values, as the only place a
  person gives what the provider checks.
- Everything already stored beyond that cleared by the migrator.

A provider account per bank is not here: it is the next design, and
the TDD and bank-providers.md carry it as a known limitation.

## Steps

### 0. The design

- [x] ADR-0045, **Proposed**.
- [x] `docs/prd/parties.md`, `docs/prd/demo-digital-bank.md`: names
      not evidence, the person's details given to the provider.
- [x] `docs/tdd/parties.md`: what the platform keeps, the simulator's
      hosted page, slices 5 to 7.
- [x] `docs/tdd/bank-providers.md`, `docs/tdd/demo-digital-bank.md`:
      the limitation and the sign-up.
- [x] Commit the design and this plan.

### 1. Slice 5 — outcomes only

The schema:

- [x] `components/schema/resources/schemas/idv/idv.proto`: an
      `IdvNameMatch` enum, `optional IdvNameMatch name_match = 7` on
      `IdvDocumentEvidence`, and `given_names`, `family_name` and
      `date_of_birth` marked deprecated.
- [x] `idv/idv-evidence.avsc.json`: `name_match` on the document, the
      three read fields removed.
- [x] `idv/submit-idv-check.avsc.json` and
      `bank-activity/idv-session-opening.avsc.json`: `date_of_birth`
      and `address` removed; the names were already there.
- [x] `components/resources/resources/system/fdb-record-types.yml`:
      `version` 80 to 81. `just force-prep`.

The domain:

- [x] `components/idv/src/.../core.clj`: the session's activity carries
      the party's names and no date of birth or address, and evidence
      handling reads no person identification.
- [x] `components/idv/src/.../domain.clj`: `decide` and
      `apply-evidence` take no `claimed`; `claimed-identity` settles
      from `name-match`, and stays outstanding without one.

The adapters:

- [x] `components/idv-provider`: `name-match`, `full-name` and
      `party-name`, the last reading the party's names through
      `person-identification` by party id.
- [x] `components/zyphe-relay/src/.../outbound.clj`: the party id in
      the run's `customData`, never a name.
- [x] `bases/zyphe-adapter`: the publisher grades the read name against
      `run-name` and drops the read fields; the webhook handler reads
      `run-name` by the `customData` party id.
- [x] `components/onfido-relay/src/.../outbound.clj`: the applicant
      from the names and the email alone.
- [x] `bases/onfido-adapter`: the same, reading `run-name` by the run's
      `customer_user_id`.
- [x] Logs in both adapters and relays: ids and statuses only, as they
      were.

The intent's request:

- [x] `components/intent-poller`: an optional `:redact` function on the
      store spec, applied to the request in the transaction that
      settles or fails the intent.
- [x] `zyphe-relay` and `onfido-relay` `store.clj`: `:redact` keeps
      only the ids, the channel and the criteria.

Proved by:

- [x] `idv` — `decide` over the treatment table, `claimed-identity`
      from each grade and outstanding without one.
- [x] Each adapter's publisher — each grade, and no read field.
- [x] `zyphe-adapter` — a webhook's outbox entry read back and
      decoded, its bytes carrying no read name or date of birth. The
      grade through a saved party is the scenarios' to prove: a brick
      test may require only what the brick does.
- [x] `intent-poller` — a settled and a failed intent read back
      redacted, a pending one and an unredacted spec untouched.
- [x] `clojure -M:poly test brick:idv:idv-provider:intent-poller:zyphe-relay:onfido-relay:zyphe-adapter:onfido-adapter:zyphe-webhook project:dev :all`.
- [x] `just test-all`, the `claimed-identity` scenarios through both
      simulators among them: 598 tests, the four failures all
      `verification-another-date-of-birth-rejects`, which asserted the
      date-of-birth check this slice removes, so the scenario goes.

Docs:

- [x] `docs/tdd/parties.md`: slice 5 "Built", the Background's "What
      reaches a provider and comes back", the adapter contract and
      "What the platform keeps" as built, and the activity log's email
      under Known Limitations.
- [x] ADR-0045: the name read by party id rather than carried by the
      run.

Found on the way:

- [ ] The bank-activity log keeps every `idv-session-opening` entry,
      email included, since nothing trims a changelog. Trimming
      published entries is its own change, not this plan's.

### 2. Slice 6 — the provider's page

- [x] `components/idv-simulator-page/src/.../core.clj`: four steps —
      details, document, selfie, address — the sandbox panel, *Leave*
      on every step, and `simulate` filling each step with the values
      producing an outcome. Every run shows every step, since the
      platform floor requires every verification, and nothing is
      prefilled, since the provider is never told the names.
- [x] `components/idv-simulator-page/src/.../decision.clj`: the
      `Submission` schema, the `outcomes`, and `decision`, mapping what
      the person entered to an outcome by the sandbox values.
- [x] `bases/zyphe-simulator` and `bases/onfido-simulator`: the decision
      route takes a `Submission` and settles by `page/decision`.
- [x] `bases/console/src/lib/Scenarios.svelte`: unchanged — its
      `simulate` hand-off plays Zaphod through the steps as it is.
- [x] `bases/demo-digital-bank-app/e2e/walkthrough.spec.mjs`: the
      provider's page driven through the four steps.

Proved by:

- [x] `idv-simulator-page` — `decision` over every sandbox value, a
      posted outcome and leaving, and the page carrying each step and
      the sandbox panel.
- [x] `zyphe-simulator`, `onfido-simulator` — their decision tests, a
      posted outcome settling as before.
- [x] A browser run of the rendered page in headless Chromium against a
      stub recording what it posts: by hand through every step, the
      empty step and the missing photo refused, the failing postcode
      carried; by `simulate` for `pep`, `liveness-failed`, `walk-away`
      and `match`; and the walkthrough's own labels and buttons. Not
      against the monolith: the decision route's settling is the
      simulators' tests' and the scenarios'.
- [x] While `simulate` plays the card is inert and the badge reads
      *Playing*: 104 real clicks on *Submit*, *Leave* and the fields,
      with typing and Enter, during a paced `pep` run left one post,
      the simulation's own.
- [x] `just test-all`: 603 tests, no failures.

Docs:

- [x] `docs/tdd/parties.md`: slice 6 "Built"; the Background's
      "Simulators" bullet, the page's design and its tests as built.

### 3. Slice 7 — names only

The schema:

- [x] `party/party.proto`: `optional string external_reference = 10`,
      and `Party_by_external_reference`, unique on `[bank_id,
      external_reference]`.
- [x] `person-identification/person-name.proto`: `PersonName`, the
      three names, in a `person-names` store since 82.
      `PersonIdentification` stays as it was: the meta-data guard
      refuses a required field made optional.
- [x] `party/create-party.avsc.json`: names and `external_reference`
      only.
- [x] `fdb-record-types.yml`: `version` 81 to 82. `just force-prep`.

The API and the domain:

- [x] `components/party-api`: `CreatePartyRequest` closed and
      narrowed, `ExternalReference` at most 128 characters,
      `PartyDetail` and `PartyEmbedQuery` to the names alone,
      `IdentifierType`, `NationalIdentifier` and `Address` retired, and
      `ExternalReferenceTaken` (409) in place of
      `IdentificationRejected` (422).
- [x] `bases/api`: the create and read routes, `errors.clj` mapping
      `:party/external-reference-taken` to 409.
- [x] `components/party`, `components/party-query`,
      `components/person-identification`: names to `person-names`, no
      national identifier written or read.
- [x] `components/api-schema`: `CountryCode`, `Country3Code`,
      `DateOfBirth`, `NationalIdentifierValue` and their date helpers
      retired.

The callers:

- [x] `bases/console`: the party drawer to names and a reference, the
      scenario's people registered under `cust-*` references, their
      dates of birth told only to the provider's page.
- [x] `components/demo-digital-bank`, `bases/demo-digital-bank-api`,
      `bases/demo-digital-bank-app`: sign-up takes names and email,
      registers the person under the sign-up's id, and an *Identity
      check* screen hands them to the provider; the photo ID and selfie
      screens retired. The walkthrough follows.
- [x] `components/test-api-scenarios`: every fixture and scenario
      stripped of the removed fields by a rewrite-clj pass; two
      scenarios added, a date of birth refused and a reference taken.
- [x] `components/test-scenarios`: the model's marker is an external
      reference, and `external-reference-taken` replaces
      `identification-rejected`.

The clearance:

- [x] `components/fdb`: `rewrite-store`, a store a page per
      transaction.
- [x] Each owning brick clears its own store: `person-identification`,
      `idv`, `party`, `intent-poller` through each IDV relay.
- [x] `components/personal-data`: the `personal-data/clearance` kind,
      in `migrator-service` with the bricks it reaches.

This is transitional: the next release starts from an empty cluster,
which the clearance meets as a no-op.

Proved by:

- [x] `party-api`, `party` — names and a reference, the 409.
- [x] `personal-data` — every former shape cleared, what is kept left,
      a rerun clearing nothing.
- [x] `migrator-service` — the schema-evolution guard over the new
      store and index.
- [x] `just test-all`: 677 tests; seven failures, the property test's
      Kafka topics refusing to start and four Modulr account polls
      timing out under the parallel load, all passing when
      `test-scenarios` and `test-api-scenarios` reran alone.

Docs:

- [x] `docs/tdd/parties.md`: slice 7 "Built", Background, the banner,
      the clearance and the changelog limitation as built.
- [x] `docs/tdd/demo-digital-bank.md`: the sign-up and the hand-off's
      flow as built.
- [x] `docs/prd/parties.md`: the reference as the duplicate guard.

### 4. After deployment

- [ ] Run the migrator on the test instance and read the clearance's
      counts from its log.
- [ ] Retire the `PartyNationalIdentifier` and `PersonIdentification`
      record types and their stores, per
      [schema-evolution](../recipes/code/schema-evolution.md) — or with
      the clean slate the next release starts from, along with the
      clearance.
- [ ] A check in `scripts/hooks/enforce-idioms.sh` refusing the removed
      field names in `components/schema`, as ADR-0045's Harder names.
- [ ] ADR-0045 **Accepted**, and the `design` rule synced with
      `sync-rules-from-docs`.

## Decisions taken in this plan

- **`match-name` stays in `party-query`.** The adapters reach it
  through `idv-provider/name-match`; every project carrying
  `idv-provider` already carries `party-query` and
  `person-identification`.
- **Redaction is the intent-poller's.** One `:redact` on the store
  spec, rather than each relay rewriting its own intents, so the
  payment relays are untouched. A relay's `:redact` keeps the keys it
  names rather than dropping the ones it fears, so a field added later
  goes by default.
- **The adapter reads the party's name.** A run carries only the party
  id, and the adapter reads the name through `idv-provider/party-name`
  when the result arrives, so no name rides to a provider in
  correlation data.
- **The clearance is the migrator's.** It already opens every store
  before the services roll, and runs once per deployment.
