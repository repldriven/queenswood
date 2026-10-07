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

- [ ] `components/schema/resources/schemas/idv/idv.proto`: an
      `IdvNameMatch` enum (`unknown`, `match`, `close-match`,
      `no-match`), `optional IdvNameMatch name_match` on
      `IdvDocumentEvidence`, and `given_names`, `family_name` and
      `date_of_birth` marked deprecated.
- [ ] `idv/idv-evidence.avsc.json`: `nameMatch` on the document, the
      three read fields removed.
- [ ] `idv/submit-idv-check.avsc.json`: `givenName`, `middleNames`,
      `familyName` in place of the person identification.
- [ ] `components/resources/resources/system/fdb-record-types.yml`:
      `version` 80 to 81. `just force-prep`.

The domain:

- [ ] `components/idv/src/.../core.clj`: the session publishes
      `submit-idv-check` with the party's names, read through
      `person-identification`, and evidence handling reads no person
      identification.
- [ ] `components/idv/src/.../domain.clj`: `decide` takes no
      `claimed`; `claimed-identity` settles from `name-match` by the
      treatment table, and stays outstanding without one.

The adapters:

- [ ] `components/zyphe-relay/src/.../outbound.clj`: the names in the
      run's `customData`.
- [ ] `bases/zyphe-adapter/src/.../publisher.clj`: grade the read name
      against the `customData` names with `party-query/match-name`,
      report `name-match`, and drop the read fields.
- [ ] `components/onfido-relay/src/.../outbound.clj`: the applicant
      from the names and the email alone; the evidence read graded
      against the applicant's names, read back with the run.
- [ ] `bases/onfido-adapter/src/.../publisher.clj`: report
      `name-match`, no read fields.
- [ ] Logs and spans in both adapters and relays: no field of a
      provider's result, no email, no name.

The intent's email:

- [ ] `components/intent-poller`: a `:redact` function on the store
      spec, applied to the intent's `request` in the transaction that
      settles or fails it; absent, the request is kept as it is.
- [ ] `zyphe-relay` and `onfido-relay` `store.clj`: `:redact` drops
      `:email`.

Proved by:

- [ ] `idv` — `decide` over every row of the treatment table, with
      `claimed-identity` from each grade and outstanding without one.
- [ ] Each adapter — each grade from its simulator's results, and the
      outbox entry's payload read back with no read field in it.
- [ ] `intent-poller` — a settled and a failed intent read back
      redacted, and an unredacted spec untouched.
- [ ] `test-api-scenarios` — the `claimed-identity` rows through both
      simulators.
- [ ] `clojure -M:poly test brick:idv:zyphe-relay:onfido-relay:intent-poller project:dev :all`,
      then `just test-all`.

Docs:

- [ ] `docs/tdd/parties.md`: slice 5 "Built", and the Background's
      "What reaches a provider and comes back" rewritten as built.

### 2. Slice 6 — the provider's page

- [ ] `components/idv-simulator-page/src/.../core.clj`: the steps,
      chosen by the run's configuration, the details prefilled with the
      run's names, the sandbox panel, *Leave* on every step, and
      `simulate` filling each step with the values producing an
      outcome.
- [ ] `components/idv-simulator-page/src/.../interface.clj`: `form`
      takes the run's names and steps.
- [ ] `bases/zyphe-simulator/src/.../verification_requests/`: the page
      served with the run's `customData` names and its flow's steps;
      the page's post mapped from sandbox values to an outcome.
- [ ] `bases/onfido-simulator/src/.../outcomes.clj` and
      `applicants/`: the same from the applicant and the workflow.
- [ ] `bases/console/src/lib/Scenarios.svelte`, `api.mjs`: the
      onboarding scenario plays Zaphod through the steps.
- [ ] `bases/demo-digital-bank-app/e2e/walkthrough.spec.mjs`: the
      walkthrough passes through the steps.

Proved by:

- [ ] Each simulator — a test per sandbox value posting its results,
      the read names and date of birth in them.
- [ ] `idv-simulator-page` — the steps per configuration, the
      prefilled names, `simulate` filling every step.
- [ ] A browser run of the page through every step, from a hand-off to
      the return URL, against the monolith.

Docs:

- [ ] `docs/tdd/parties.md`: slice 6 "Built"; the Background's
      "Simulators" bullet as built.

### 3. Slice 7 — names only

The schema:

- [ ] `party/party.proto`: `optional string external_reference`.
- [ ] `person-identification.proto`: `date_of_birth`, `nationality`
      and `address` optional and deprecated.
- [ ] `party/create-party.avsc.json`: names and `externalReference`
      only.
- [ ] `fdb-record-types.yml`: `version` 81 to 82. `just force-prep`.

The API and the domain:

- [ ] `components/party-api/src/.../components.clj`:
      `CreatePartyRequest` closed and narrowed, `external-reference`
      at most 128 characters; `PartyDetail` without the removed
      fields; `PartyEmbedQuery` without `address` and
      `national-identifier`. `examples.clj` to match.
- [ ] `bases/api/src/.../party/`: commands, queries, routes and
      `shared/parameters.clj` without the removed fields.
- [ ] `components/party`, `components/party-query`,
      `components/person-identification`: nothing writes a
      `PartyNationalIdentifier`, a date of birth, a nationality or an
      address.
- [ ] `components/api-schema`: the removed shared schemas, where
      nothing else refers to them.

The callers:

- [ ] `bases/console/src/lib/`: `PartyDrawer.svelte`,
      `People.svelte`, `PeopleDrawer.svelte`, `Onboarding.svelte`,
      `Scenarios.svelte`, `api.mjs`.
- [ ] `components/demo-digital-bank`, `bases/demo-digital-bank-api`
      (`sign_up/components.clj`), `bases/demo-digital-bank-app`
      (`Onboarding.jsx`, the photo ID and selfie screens retired).
- [ ] `components/test-api-scenarios/test-resources`: every scenario
      and fixture registering a person with names alone.
- [ ] `components/test-scenarios`: the model and its generators.

The clearance:

- [ ] `bases/migrator`: a clearance step after the meta-data is saved,
      clearing the person identification's three fields, the `Idv`
      read fields, the `idv-evidence` outbox payloads' read fields and
      the IDV intents' email, and deleting every
      `PartyNationalIdentifier`.

Proved by:

- [ ] `party-api` — a create carrying each removed field refused 400.
- [ ] `party` — a person created with names and a reference.
- [ ] `migrator` — the clearance over records holding every cleared
      field, and a second run finding nothing.
- [ ] `just test-all`.

Docs:

- [ ] `docs/tdd/parties.md`: slice 7 "Built"; the Background's
      "Person identification" bullet as built.
- [ ] `docs/tdd/demo-digital-bank.md`: the sign-up as built.

### 4. After deployment

- [ ] Run the migrator on the test instance and read the clearance's
      counts from its log.
- [ ] Retire `PartyNationalIdentifier`'s record type and its store, per
      [schema-evolution](../recipes/code/schema-evolution.md), once
      every instance has run the clearance.
- [ ] A check in `scripts/hooks/enforce-idioms.sh` refusing the removed
      field names in `components/schema`, as ADR-0045's Harder names.
- [ ] ADR-0045 **Accepted**, and the `design` rule synced with
      `sync-rules-from-docs`.

## Decisions taken in this plan

- **`match-name` stays in `party-query`.** The adapters call it
  through its interface; `external-adapters-service` already carries
  the brick.
- **Redaction is the intent-poller's.** One `:redact` on the store
  spec, rather than each relay rewriting its own intents, so the
  payment relays are untouched.
- **The clearance is the migrator's.** It already opens every store
  before the services roll, and runs once per deployment.
