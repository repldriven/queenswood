# Scenario Testing

> **Status: implemented.**

## Objective

Queenswood's tests are of three kinds: a brick's own tests, domain
scenarios that drive component interfaces beside a pure model of the
bank, and API scenarios that drive the public HTTP surface. This TDD
decides what each kind is for and the rule that places a case in exactly
one of them, how each scenario runner and its corpus are shaped, and how
the line between the kinds is enforced.

In scope: the three tiers and the placement rule; what a brick test may
call, the semgrep rule and scope check that hold it, and what stays in a
brick test; the `test-scenarios` runner, its verb kinds, its corpus and
the model-equality property, with the `test-model` and `test-projections`
pairs it compares; and the `test-api-scenarios` runner, its fixtures,
schema, layout, PRD journeys, provider runs, waits and concurrency.

Out of scope: journey scenarios for the PRDs that have none yet, which
Known Limitations lists;
`with-test-system`, its permits and the runner's parallelism, which
mono's [test-system](../recipes/test/test-system.md) covers; the commands
that run each tier and the service-project test matrix, which
[testing](../recipes/test/testing.md) covers; and the demo bank's own
journeys, which [demo-digital-bank.md](demo-digital-bank.md) covers.

## Background

- **The test system.** mono's `test-system`: `with-test-system` boots a
  brick's or rig's YAML, holding one of `TEST_SYSTEM_PERMITS` permits
  while its system is up; `nom-test>` asserts anomaly-freeness.
- **The FDB container.** `testcontainers`' `fdb.clj` builds the server
  image once per hash of its build context, on a tmpfs data directory,
  and CI caches the image.
- **fugato.** Command specs over a pure state map, generated as
  sequences and shrunk on failure, under
  [ADR-0009](../adr/0009-model-equality-property-testing.md).
- **The standing invariants.** The trial-balance tie and the sub-ledger
  to control reconciliation, which
  [chart-of-accounts.md](chart-of-accounts.md) defines. Interest is
  reconciled separately, since its two sides commit in separate
  transactions.
- **Providers and their simulators.** Every payment and IDV adapter runs
  beside its simulator, each declaring what it can do, as
  [bank-providers.md](bank-providers.md) sets out; the payment
  simulators share `scheme-simulator`.
- **The test realm.** `test-resources`' `queenswood-realm.json`: the
  Keycloak realm a rig imports, with its clients and the users scenarios
  sign in as.
- **matcher-combinators.** The markers a body assertion is written in,
  `[:m/regex …]`, `[:m/embeds …]` and `[:m/seq-of …]`.
- **The pre-commit checks.** The semgrep rules and
  [enforce-idioms.sh](/scripts/hooks/enforce-idioms.sh), run on staged
  files and over the tree in CI, as
  [git-hooks](../recipes/practices/git-hooks.md) describes.

## Solution

### Three tiers

Every test is one of three kinds, each with one job:

- **A brick test** proves a brick's own logic: its pure functions, and
  its own store and changelog against FDB under `with-test-system`, read
  back through its own query sibling.
- **A domain scenario**, in `test-scenarios`, proves the bank's rules
  over sequences of commands, driven through component interfaces. It is
  either *compared*, the model running beside it and its projections
  equal after every step, or *reality-only*, for a case the API cannot
  reach and the model has no rule for.
- **An API scenario**, in `test-api-scenarios`, proves the HTTP contract
  and the PRDs' user journeys through real requests.

A case goes to the first tier whose question it answers yes:

1. Is it a pure function, or the brick's own store or changelog? A brick
   test.
2. Is it what a client sees: a status, a problem body, a header, a link,
   an auth boundary, or a step of a PRD journey? An API scenario.
3. Is it a rule over a sequence the model can express? A compared domain
   scenario.
4. Can a client reach it through the public API, including the provider
   simulators the rig boots? An API scenario.
5. Otherwise, a provider event with no simulator route, a dead letter, an
   intent or a scheme command: a reality-only domain scenario.

A case lives in one tier. Where a domain scenario and an API scenario
assert the same thing, the API scenario keeps it unless the domain one
is compared. A test that is the only coverage of a case moves to its
scenario before it is deleted.

### What a brick test may do

A brick test never sends a command or an event, and never writes a
record through another write brick's interface. It may call its own
brick's namespaces, read through any `*-query` brick, and write another
brick's store directly where it is a query brick reading across stores,
as `cash-account-query` does. `changelog-relay`, whose output is the bus,
and the `test-*` bricks are exempt. A rig boots what its tests use.

Two checks hold the line:

- **`brick-test-drives-pipeline`** in
  [semgrep.yml](/.config/semgrep/semgrep.yml) matches
  `processor/process`, `commands/dispatch`, `message-bus/send`,
  `message-bus/subscribe` and `event/publish` under
  `/components/*/test/**` and `/bases/*/test/**`, excluding the `test-*`
  bricks and `changelog-relay`. A site that stays carries its reason on a
  comment line, then `;; nosemgrep: brick-test-drives-pipeline` directly
  above it.
- **`brick-test-scope`** in `enforce-idioms.sh` refuses a brick or base
  test requiring anything beyond test infrastructure, a `*-query` brick
  and what the brick's own `src` requires, short of a
  `;; enforce-idioms: brick-test-scope -- <reason>` marker.

### What stays in a brick test

Some brick tests cross the line on purpose, each carrying its reason:

- **An adapter's own command processor.** `clearbank-adapter` and
  `modulr-adapter` hand their processor a command to prove the intent it
  writes and that a redelivery writes no second one; no scenario
  redelivers a command.
- **A brick's own event handler.** `email`'s deliveries test and
  `webhook`'s events test hand the handler an envelope with no bus, to
  prove a redelivered event produces one delivery.
- **The cap race.** `cash-account-product`'s concurrent creates at the
  cap hold both creates past their reads on a latch, which no race over
  HTTP can promise.
- **The bank's rollback.** `bank`'s test of a failure after the last
  write rolling every earlier write back calls other bricks only to read
  what was written.
- **Membership concurrency.** `membership`'s conflicting writes and its
  latch tests prove what one transaction reads, against its own store.

### The domain runner

**Verb kinds.** Every verb is declared in the `verbs` map in
`scenario.clj` with one kind and a closed Malli schema of its arguments:

- `:model`: a command the model has a spec of the same name for.
  `:open-account` is one fugato never generates.
- `:fixture`: a write beneath the domain that sets up a state the domain
  then reacts to, and that the model mirrors: `:fixture/apply-fee`, and
  `:fixture/fund-house`, whose house account the model does not hold.
- `:reality`: a production path the model has no rule for — a provider
  event such as `:settle-outbound-event`, a redelivered submit, an
  admission, a ledger account closed.
- `:read` and `:assert`: change no state, and never stop a comparison.

**Compared or reality-only.** A scenario carries `:model :compared`, the
default, or `:model :reality`. A compared scenario naming a `:reality`
verb is refused at load with `:test-scenarios/scenario`, naming the verb
and the file.

**Shape.** `:given` holds the steps that change state, `:when` the steps
under test, and `:then` only `:read` and `:assert` steps.

**The run.** `runner.clj` dispatches each step, then checks both standing
invariants after any step that changes state; `invariants/check` returns
failures as data, kept as `{:index :command :failures}`. A step that
times out is recorded `:timed-out`, never as a rejection the model would
accept, and stops the run as a runner error. A compared scenario runs
through `divergence.clj`, which advances the model in step, projects both
sides after every step, and stops at the first step after which they
differ, naming it with what is only in the model and only in reality, as
[ADR-0009](../adr/0009-model-equality-property-testing.md) promises.

**The property test.** `property_test.clj` runs 50 trials of up to 30
generated commands against one booted system. A trial fails on a runner
error, a broken invariant or end states that differ, so fugato shrinks
any of them, and the shrunk sequence is walked to its first divergence.

**The model and projections.** `:create-bank`, the costliest command,
takes a `:freq` a quarter of every other command's. `projection.clj`
pairs each real-side projection in `test-projections` with its
model-side one: balances, accrued interest and carry, products, parties,
banks, accounts, transaction leg counts, outbound payments, and inbound
payments, found by the scheme transaction ids the runner records. A
projection reads through the query path the API uses rather than the
store, and lets no timestamp or generated id through.

**Policies.** The model is held to the policies reality boots with:
`model-init` reads the platform policy and the tier policy scenario
banks are created on out of the rig's own configuration, and hands
them to `test-model` as data, so the model drifts from neither. Every
bank is held to the platform policy, `:create-bank` binds the tier, and
`:bind-policy`, a `:model` verb fugato never generates, binds a
scenario's own. The model's evaluator, written apart from the `policy`
brick's, reads the shapes it acts on: capabilities, where an action
needs an allow and a deny wins; count limits, daily on payments and
interest runs and instant on accounts, which count the bank's house
account; and the available balance limit. `:bind-policy`'s schema
admits only those shapes.

**Waiting.** `await.clj` is the one loop, with one timeout,
`:await-timeout-ms` on the runner context. `:close-account` waits for no
provider: the close records `closing` before it returns, which the
projection reads as closed.

### The domain corpus

The directories take the API corpus's names — `payments/`,
`cash-accounts/`, `cash-account-products/`, `parties/`,
`ledger-accounts/` and `providers/` — with `interest/` for the interest
runs. The reality-only files hold what the API cannot reach: a
dead-lettered settlement, a provider event delivered twice, provider
balance mirroring, a redelivered submit, the intent and scheme-command
assertions, admissions, and a closed control met by a posting. The
policy scenarios — daily limits, a denied capability, the account cap, a
waived close and an interest run limit — and the held inbound scenarios
are compared.

### API scenario fixtures

A fixture is a named, parameterised block of steps in
`test-resources/test-api-scenarios/fixtures/<name>.edn`:

```clojure
{:doc "A bank on the run's providers, with a token minted for it."
 :params {:name "Scenario Bank"
          :status "test"
          :tier "micro"
          :currencies ["GBP"]}
 :steps
 [{:command :api/request
   :request {:method :post
             :path "/v1/banks"
             :auth :admin
             :body {:name [:param :name]
                    :status [:param :status]
                    :tier [:param :tier]
                    :currencies [:param :currencies]}}
   :as :bank
   :token-as :token
   :assert {:status 201}}]}
```

A scenario step names one, its parameters and an alias:

```clojure
{:fixture :funded-account :as :acme :with {:amount 250000}}
```

`scenario.clj` expands fixtures at load, so the runner sees only steps.
A fixture may use another. Its captures are reached through the
instance's alias, `[:ref :acme :account :cash-account-id]`, so two
instances never collide.

### The API scenario shape

**Sections.** `:given` holds fixtures and requests whose assertions are
status only, `:when` the steps under test, and `:then` reads, polls,
mail and assertions, never a write. A scenario's `:name` is its
`testing` label.

**Schema.** Each verb has a closed schema in `scenario.clj`. `:assert`
means one thing, `{:status :body :headers :problem}`, on `:api/request`
and `:api/poll`'s `:until`; `:api/race` adds `:fresh`.

**Explicit effects.** Nothing a verb does is hidden:

- The token a bank create mints is the `:bank` fixture's, captured by
  `:token-as`; `:auth/mint-token` mints a second.
- Identity verification is a step, `:idv/verify`, which the `:person`
  fixture carries, and a bare `POST /v1/parties` verifies nothing.
- A lost reply is declared on its step, `:fault :lost-reply`.

**Idempotency keys.** A write with no `Idempotency-Key` header is given
one made of the execution's run id and a counter, and
`:idempotency-key false` sends none. A literal is written only where a
scenario proves a replay, and every literal is unique across files.

**Layout and names.** One directory per OpenAPI tag, named for it in
kebab case, holds the scenarios whose subject is a route under that tag:
`me/`, `memberships/`, `invitations/` and `audit/` where a person's
access to a bank is concerned, `banks/`, `companies/`, `oauth/`,
`parties/`, `payee-checks/`, `cash-account-products/`, `rewards/`,
`cash-accounts/`, `cash-account-migrations/`, `payments/`,
`ledger-accounts/`, `jobs/`, `webhook-endpoints/` and `simulate/`. Three
directories hold what no one tag does: `auth/` the token and role checks
every route makes, `providers/` the runs across payment and IDV
providers, and `journeys/` the PRDs' user journeys. A file is named for
the behaviour, a refusal ending `-refused` and a missing resource
`-not-found`.

**Tags.** One closed vocabulary: `:serial`. Provider capabilities are
`:requires`.

### PRD journeys

A PRD's user journeys are scenarios under `journeys/<prd>/`, one file per
journey, named for its heading: `### 2. Outbound payment (happy path)` is
`2-outbound-payment-happy-path.edn`. A journey's steps are the beats of
the PRD's diagram, the path it draws, asserted as the customer sees
them: the reply, the balances, the record the customer reads and the
notification its endpoint is sent. A journey runs on every provider of
the kinds it touches, `:runs-on {:payment :every}` for a payment.

The tag directories keep what a journey does not assert: refusals,
replays, races and faults, a route's contract shape, such as its paging,
filters and headers, and what one provider does differently. A journey
deletes, in the change that adds it, a tag scenario that asserted only
its path, and a tag scenario that asserted that and more keeps the rest.

A journey the platform cannot run yet carries `:unbuilt` and the reason,
is skipped on every run and reported with it, and starts running when
the key goes.

`corpus_test.clj` holds each `journeys/<prd>/` directory to its PRD:
every numbered journey has a file, and every file is a journey. A PRD
with no directory is not checked.

### Provider runs

A scenario declares the providers it runs on: `:runs-on {:payment
:every}`, `{:idv :every}`, or nothing for the defaults alone. `:requires`
names the capabilities it needs, checked against each provider's
declaration, and the rig's `application-test.yml` carries the `unbuilt`
capabilities on its `test-api-scenarios/settings` component. A bank
create naming no providers is sent with the run's.

A tag scenario runs on every provider only where what it asserts passes
through the provider: the provider's messages and refusals, a capability
its declaration names, an identity verification's decision or a payee
check's answer. Idempotency, another bank's read, a list, a not-found and
a refusal answered before any provider is called run on the defaults,
since the journeys prove each provider end to end. A skipped run is
logged with its reason and counted in the run's summary, and reports no
`testing` block.

### Waiting

`await.clj` is the API runner's one loop: it re-runs a step until its
assertion holds or a deadline passes, and `:api/poll`,
`:mail/await-invitation`, `:idv/verify` and the invariants' settle all
use it. The timeout is one config key, `await-timeout-ms`, in the rig's
YAML. `:wait` is for a token's expiry only. `:webhook/await-delivery`
uses the same loop, so a delivery's wait reaches past the relay to the
bus, the webhook consumer's commit, the runner's poll and its call.

### Webhook delivery

The rig hosts the webhook consumers and runner, and a receiver: mono's
Jetty adapter on 127.0.0.1, keeping every request it is sent. Its
address rule admits plain HTTP and loopback at registration, and its
runner sends over plain HTTP only, so an HTTPS address a scenario
registers is refused at send time and never called.

- `:webhook/open-receiver` captures an address on the receiver no other
  step uses.
- `:webhook/await-delivery` waits for `:count` requests to it, checks
  each signature under the endpoint's `:secret`, and captures each
  request's headers and parsed body.
- `:assert/equals` holds two captured values exactly equal, with no
  matcher between them: a notification's `data` and the read route's
  body.

### Running concurrently

`api-scenarios-test` runs scenarios on a pool of `workers` threads, a
config key set from `!env TEST_API_SCENARIO_WORKERS` and defaulting to
the processors available, each task wrapped in `bound-fn` so
`clojure.test`'s counters and contexts carry. `:serial` scenarios run
after the pool drains, one at a time, then the span assertions run.

Three things keep scenarios apart:

- **A verification email each.** The Zyphe simulator resumes a pending
  run for the same person, as Zyphe does, so each verification sends an
  address of its own.
- **The global refusal.** `POST /simulate/open-refused` refuses the next
  account any bank opens, so `cash-accounts/open-refused.edn` is
  `:serial`.
- **Every bank's page.** `banks/bank-list-owners.edn` reads the
  newest-first first page of every bank, and is `:serial`.

Before each `:serial` scenario the runner waits until no account a bank
the run created holds is still opening, so a refusal set for the next
account opened is not answered for one an earlier scenario left in
flight.

### Tests

- **`test-api-scenarios`.** `corpus_test.clj`, booting nothing, holds
  that every scenario and fixture validates, every fixture is used, every
  key literal is unique, and each PRD's journey directory names its
  journeys. `api-scenarios-test` runs every scenario
  on each provider it declares, reports each skip, and asserts the run's
  spans. `realm_test.clj` holds the deployed realm's token claims.
- **`test-scenarios`.** `corpus_test.clj`, booting nothing, holds that
  every scenario validates, that the verb table and the dispatch methods
  name the same verbs, that the `:model` and `:fixture` verbs are the
  model's commands, and that a compared scenario naming a reality verb,
  a step in the wrong section, a wrong argument and an unknown verb are
  each refused. `scenarios-test` runs every scenario, compared or not,
  and fails on a divergence, an invariant failure or a runner error. A
  divergence is shown to name its first step, a timed-out step and books
  a step leaves untied are each shown to fail a trial, and the property
  test runs on the weighted model.
- **`test-model`.** Each command's `:next-state` in isolation, and the
  weights.
- **`test-projections`.** Each model-side projection over a hand-built
  model state, `project-model-interest` included.
- **The checks.** `just semgrep` and `enforce-idioms.sh --all` pass over
  the tree with no opt-out but those What stays in a brick test lists.

## Alternatives Considered

- **One scenario brick for both layers.** Rejected: the domain runner
  reaches FDB and the model, the API runner holds no FDB config and reads
  only the API, and one brick would carry both into every rig.
- **API scenarios only.** Rejected: model equality, and the provider
  events, dead letters and intents no route reaches, need the domain
  runner. Taken in part: domain files an API scenario repeats go.
- **Every domain scenario compared.** Rejected: provider events, dead
  letters and intents have no model rule, and writing one would make the
  model look like production, which
  [ADR-0009](../adr/0009-model-equality-property-testing.md) warns
  against.
- **Fixtures as Clojure functions.** Rejected: setup would leave the
  scenario file and stop being data a reviewer reads; fixtures stay EDN,
  expanded at load.
- **Fixtures as file includes, without parameters or aliases.**
  Rejected: two instances in one scenario would collide on captures, and
  names, tiers and amounts differ between files.
- **A system per scenario, for concurrency.** Rejected: a boot costs more
  than most scenarios, and one system already serves them all.
- **A refusal keyed to one account in the simulators.** Rejected for now:
  one scenario uses the refusal, and running it alone costs that
  scenario's time.
- **The brick-test reach check in `enforce-idioms.sh`.** Rejected: it
  reads one file's tokens, which belongs in the semgrep rules, where a
  site gets an opt-out.
- **Directories by PRD.** Rejected: a scenario's subject is a route, and
  the OpenAPI tags already group the routes. Taken in part: a PRD's
  journeys, which cross tags, sit under `journeys/<prd>/`.
- **Tag scenarios reduced to refusals, with journeys carrying every happy
  path.** Rejected: a route's happy path also pins its contract, its
  shape, paging, filters and replays, which no journey asserts, and a
  failing tag scenario names its route where a journey names a step.
- **Deleting every crossing brick test at once.** Rejected: some were the
  only coverage of a refusal, and moved first.

## Known Limitations

- **The model has no rule for provider events.** A scenario that
  delivers a provider event, redelivers a submit or asks for an
  admission runs reality-only.
- **The model reads part of a policy.** Filtered capabilities and
  amount limits are not read, and `:create-product` and
  `:create-person-party` meet no count limit, since no scenario comes
  near one.
- **PRDs with no journey scenarios.** Only `payments`, `cash-accounts`,
  `parties`, `cash-account-products`, `policies` and `onboarding` have a
  directory under `journeys/`. The access, interest, platform and
  webhooks PRDs have none, so their journeys go unchecked, and
  `journeys/full-happy-path.edn` covers parts of several of them.
- **Unbuilt journeys.** `cash-accounts/2` is skipped, since no template
  a customer's account opens under allows a second currency;
  `parties/2`, since the create route takes person parties only;
  `policies/1` and `policies/4`, since no route creates, binds or
  updates a policy; and `policies/3`, since no route takes an available
  balance below zero for a deposit to improve.
- **Cases with no scenario.** Curative transfers and daily limits over
  HTTP; organisation parties; the payment
  refusals the domain corpus holds and the API does not; and routes no
  scenario calls, among them the tier reads, a balance by
  type, a migration's cancel, a run by id, webhook test notifications and
  resends, and the discovery documents.
- **The demo bank's tests.** `demo-digital-bank`'s interface test and the
  `demo-digital-bank-api` base test drive its own app end to end, and
  have no scenario tier to move to.
- **Scenarios share one installation.** A scenario that reads across
  banks has to tolerate the others' data, and one that changes
  installation-wide state has to be `:serial`; nothing detects either.
- **Concurrency adds load.** Each step's invariants read every bank the
  scenario holds a token for, and the await deadlines are sized for a
  loaded rig rather than an idle one.

## References

- [platform](../prd/platform.md) — the platform PRD whose journeys the
  API scenarios prove, and each capability PRD beside it.
- [ADR-0009](../adr/0009-model-equality-property-testing.md) — Model
  equality property testing, the decision the domain runner implements.
- [testing](../recipes/test/testing.md) — the placement rule, the checks
  and the commands that run the tiers.
- [test-system](../recipes/test/test-system.md) — `with-test-system`,
  `nom-test>` and the runner's permits, mono's recipe.
- [chart-of-accounts.md](chart-of-accounts.md) — the two invariants
  both runners assert after every step.
- [bank-providers.md](bank-providers.md) — the providers a scenario runs
  on, and their declarations.
- [webhooks.md](webhooks.md) — the notification contract the delivery
  scenario holds.
- [idempotency.md](idempotency.md) — the replay and race contract the
  API scenarios prove.
- [service-apis.md](service-apis.md) — the API surface the scenarios
  drive, and its OpenAPI tags.
- [fugato](https://github.com/vouch-opensource/fugato) — the
  command-sequence generator and shrinker.
