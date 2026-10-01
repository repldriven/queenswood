# Scenario Testing

> **Status: proposal.** Both scenario bricks, the model, the projections,
> the standing invariants and the brick-test scope check exist, and
> Background describes them as they were before slice 1. Slice 1 is
> built: the API runner's closed schema, fixtures, generated keys,
> declared fault and token, provider declarations, await and worker
> pool, with two scenarios on fixtures. Proposed Solution is the design
> the tests move to, and its build list. "Five slices" gives the order.

## Objective

Queenswood's tests are of three kinds: a brick's own tests, domain
scenarios that drive component interfaces beside a pure model of the
bank, and API scenarios that drive the public HTTP surface. This TDD
decides what each kind is for and the rule that places a case in exactly
one of them, how each scenario runner and its corpus are shaped, how the
line between the kinds is enforced, and the order the existing tests move
into that shape.

In scope: the three tiers and the placement rule; what a brick test may
call, and the semgrep rule and scope check that hold it; the
`test-scenarios` runner, its verb kinds, its corpus and the
model-equality property, with the `test-model` and `test-projections`
changes the runner needs; the `test-api-scenarios` runner, its fixtures,
schema, provider runs, waits and concurrency; where each test that
crosses a tier's line goes; and the testing recipe and the documents that
describe the runners.

Out of scope: scenarios for PRD journeys that have none, which each
capability's TDD schedules (Known Limitations lists them); the webhook
delivery scenarios, which [webhooks.md](webhooks.md) schedules on the
verbs this design adds; `with-test-system`, its permits and the runner's
parallelism, which mono's [test-system](../recipes/test/test-system.md)
covers; the service-project test matrix, which
[testing](../recipes/test/testing.md) covers; and the demo bank's own
journeys, which [demo-digital-bank.md](demo-digital-bank.md) covers.

## Background

- **The placement rule.** [testing](../recipes/test/testing.md): a
  `deftest` covers a brick's pure functions and its own store and
  changelog under `with-test-system`; anything that crosses a brick
  boundary or drives the command pipeline is a scenario; the HTTP
  contract is an API scenario, never a brick `interface_test.clj`.
- **The scope check.** `brick-test-scope` in
  [enforce-idioms.sh](/scripts/hooks/enforce-idioms.sh) refuses a
  brick-test require outside test infrastructure, `*-query` bricks and
  what the brick's own `src` requires. It reads requires, not calls, and
  lists bricks from `components/` only. A brick whose `src` requires
  `policy`, `party` or `ledger-account` may build those records in its
  tests and pass.
- **Brick tests that are scenarios.** `bank/interface_test.clj` dispatches
  commands through `#'commands/dispatch` and reads back the other bricks a
  create writes; `idv/interface_test.clj` drives the IDV command and event
  processors through a session; `reward/interface_test.clj` funds the house
  through `ledger-account` and `transaction` and runs the reward lifecycle;
  `webhook/end_to_end_test.clj` runs changelog, bus, consumer, delivery runner
  and receiver; `payee-check/interface_test.clj` sends an Avro command through
  `processor/process`; `membership/interface_test.clj` tells a seven-step
  removal story; `payment/sweep_test.clj` and the clearbank, modulr, onfido and
  zyphe relay tests subscribe to the bus and wait on a promise, the relays
  re-testing what `changelog-relay` publishes. Most repeat an API scenario step
  for step.
- **API contract in base tests.** `bases/api/test/` holds handler tests
  that assert status codes, problem types, `Location` headers and
  cross-bank 404s through `with-redefs` doubles, which is why those
  namespaces carry `^:eftest/synchronized`: `webhook/writes_test.clj`,
  `access/handlers_test.clj`, `bank/person_create_test.clj`,
  `cash_account/queries_test.clj` and parts of `auth_test.clj`,
  `bank/commands_test.clj` and `oauth/handlers_test.clj`. Most repeat an
  `access/`, `auth/` or `me/` scenario. They are the only coverage of a
  webhook endpoint's 404s and invalid-address 422, a create replay, and
  `:invitation/already-exists` and `:membership/already-exists`.
- **The model.** `test-model`: a map of fugato command specs over a pure
  state map, with its own re-implementation of the policy rules it needs,
  under [ADR-0009](../adr/0009-model-equality-property-testing.md). No
  spec has a `:freq`, so `:create-bank`, the costliest verb, is generated
  as often as any other. `:activate-party` is unreachable, since every
  party is created active, and `:settle-outbound-payment` changes
  nothing, since `:outbound-payment` already completes the payment. The
  model tracks accrued interest and carry, which nothing projects.
- **The projections.** `test-projections`: real-side and model-side pairs
  for balances, products, parties, banks, accounts, transaction leg
  counts, and outbound and inbound payments. A projection takes whatever
  the system holds and returns the model-shaped subset, reads through the
  query path the API uses rather than the store, and lets no timestamp or
  generated id through.
- **The domain runner.** `test-scenarios`: one `dispatch` multimethod in
  `verbs.clj` carrying modelled commands, reads, assertions and verbs
  with no model rule alike. `interface_test.clj` folds every EDN scenario
  through the runner and the model, comparing projections after each
  modelled step, and stops comparing at the first step that is neither
  modelled nor in a hand-kept `assertion-verbs` set, with nothing in the
  output saying so; most scenarios stop during `:given`, before the step
  they exist for. `:outbound-transfer`, `:apply-fee` and `:fund-house`
  write postings straight through `fdb/transact`, a path no production
  command takes. `quiescence/wait` returns at once, and each verb polls
  its own record instead. A timed-out verb is recorded as a rejection.
- **The property test.** `property_test.clj`: 50 trials of up to 30
  generated commands against one booted system, comparing end states
  only. The per-step invariants assert with `clojure.test/is`, so a
  broken invariant inside a trial is reported but does not falsify the
  property and is never shrunk.
- **The domain corpus.** One flat directory of EDN files. Tags are read
  by nothing. `:given`, `:when` and `:then` are concatenated, so actions
  sit in `:then` and assertions in `:given`. Setup is copied between
  files. Several files repeat an API scenario: the full happy path, held
  inbound, outbound returns, admission, migration, a forced job run, the
  product-count cap and the not-found error kinds. The model's
  `:inbound-transfer` is a scheme inbound and shares nothing but a name
  with the API's simulate route.
- **The API runner.** `test-api-scenarios`: a `dispatch` multimethod in
  `verbs.clj` over `:api/request`, `:api/poll`, `:api/race`, the
  `:auth/*` verbs, `:idv/verify`, `:mail/await-invitation`, `:wait` and
  `:keycloak/add-signing-key`, refs in `refs.clj`, matcher-combinators
  markers, and an open step schema in `scenario.clj`. A bank create mints
  that bank's token as a side effect, and most files mint it again; a
  person-party create runs identity verification unless the step says
  `:verify false`; a lost reply is triggered by an idempotency key
  starting `ik-lost-reply-` through the seam in `fault.clj`. Separate
  loops poll, each with its own timeout. `:assert` and `:as` mean different
  things on different verbs.
- **The API corpus.** Directories mostly by resource, some by capability,
  one by journey. No file reuses another's setup, and setup is most of
  the corpus's lines. Most files have no `:then`. Every tag but the
  provider-capability tags is read by nothing. `rejected` and `refused`
  name the same outcome.
- **Provider runs.** `api-scenarios-test` runs every file on the default
  providers, then the `payments/` and `payee-checks/` files on each other
  payment provider and the `parties/` files on each other IDV provider,
  chosen by directory and skipped by tag, with a hand-kept `unbuilt` map.
  The provider reaches the bank create by a match on the literal path
  `/v1/banks`. A skipped file still reports as a passing `testing` block.
  Provider-dependent files outside those directories, such as
  `cash-accounts/pay-*` and `cash-accounts/open-refused.edn`, run on the
  defaults only.
- **The API test namespace.** One `deftest` boots one system and runs
  every scenario in turn in a `doseq`, then asserts the run's spans.
  Beside it, `closed-control-refuses-a-posting-test` boots a second
  system to close a ledger account through its interface, and
  `idempotency-keys-are-unique-across-files-test` lints the corpus with
  no system.
- **The standing invariants.** Both runners assert the trial-balance tie
  and the sub-ledger to control reconciliation after every step, in each
  brick's `invariants.clj`, from opposite sides: `test-scenarios` from
  one `fdb/transact` snapshot, `test-api-scenarios` from
  `GET /v1/ledger-accounts` and a paged walk of
  `GET /v1/cash-accounts?embed[balances]=true` for every bank it holds a
  token for. A read that fails is an assertion failure, never a zero.
  [chart-of-accounts.md](chart-of-accounts.md) says what the two
  invariants mean. Interest is reconciled per scenario by
  `:assert-interest-reconciliation`, since its two sides commit in
  separate transactions.
- **Idempotency over HTTP.** `:assert` takes `:headers` to match a
  replay marker; `:api/race` sends one request at once and asserts the
  invariant rather than the timing; every `Idempotency-Key` literal is
  unique across files, a repeat within a file being how a replay is
  written.
- **Cost.** Only the development project carries the scenario bricks, so
  its CI job is the longest. The API scenarios, run one at a time, are
  most of it.

## Proposed Solution

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
is compared.

### What a brick test may do

A brick test never sends a command or an event, and never writes a
record through another write brick's interface. It may call its own
brick's namespaces, read through any `*-query` brick, and write another
brick's store directly where it is a query brick reading across stores,
as `cash-account-query` does. `changelog-relay`, whose output is the bus,
and the `test-*` bricks are exempt.

Two checks hold the line:

- **`brick-test-drives-pipeline`**, a new rule in
  [semgrep.yml](/.config/semgrep/semgrep.yml), matching
  `processor/process`, `commands/dispatch`, `message-bus/send` and
  `message-bus/subscribe` under `/components/*/test/**` and
  `/bases/*/test/**`, excluding the `test-*` bricks and `changelog-relay`.
  A site that must stay carries `;; nosemgrep: brick-test-drives-pipeline`
  with its reason on the line above.
- **`brick-test-scope`** in `enforce-idioms.sh` lists bases as well as
  components, so a base test requiring another base, as
  `uk-companies-house-adapter`'s does the simulator, is refused.

A rig boots what its tests use. The payment rig's processor and event
processor, the transaction rig's processor and the webhook rig's three
consumers are booted by tests that call none of them, and come out of
those `application-test.yml` files.

### Where the crossing tests go

A test that is the only coverage of a case moves before it is deleted:
the scenario that replaces it lands first. Otherwise a test an existing
scenario repeats is deleted.

Components:

- **`bank/interface_test.clj`.** The dispatch, replay, provider, tier and
  status tests go, `banks/*.edn` and `access/*.edn` covering them. The
  changelog dedup test stays. The rollback test is narrowed to the bank's
  own record and changelog.
- **`idv/interface_test.clj`.** `process-idv-test` and
  `session-and-evidence-test` go, the `parties/verification-*.edn`
  scenarios covering them. The rest are narrowed to `core` and the store.
- **`reward/interface_test.clj`.** Goes. The defer and pay cases are
  `rewards/opening-reward-*.edn`; a rerun paying nothing twice becomes an
  API scenario forcing the job twice.
- **`webhook/end_to_end_test.clj`** and the bus-subscription test in
  `webhook/events_test.clj`. Go once the delivery scenarios in
  [webhooks.md](webhooks.md) slice 2 pass.
- **`webhook/interface_test.clj`.** The lifecycle and count-limit tests go,
  `webhook-endpoints/endpoint-lifecycle.edn`, `secret-rotation.edn` and
  `endpoint-count-limit.edn` covering them.
- **`membership/interface_test.clj`.** The removal-and-reinvitation story
  becomes an `access/` scenario, and the test building a user through
  `user/upsert-by-sub` goes. The concurrency and latch tests stay.
- **`payee-check/interface_test.clj`.** `process-check-payee-test` is
  narrowed to the core call it wraps.
- **`payment/sweep_test.clj`.** Narrowed to `sweep-once` and the actions
  it returns, with no bus.
- **The four relays' interface tests.** The publish-to-bus cases go;
  `changelog-relay`'s own test covers the handler.
- **`cash-account-product/interface_test.clj`.** The concurrent creates
  at the cap become an API scenario using `:api/race`.
- **`ledger-account/interface_test.clj`.** Closing an account with a
  balance becomes a reality-only domain scenario.
- **`scheduler/core_test.clj`.** The cron-trigger wait goes; it tests the
  scheduling library.

Bases:

- **`api/webhook/writes_test.clj`.** Becomes `webhook-endpoints/`
  scenarios for an invalid address, a missing endpoint and delivery, and
  a create replay, using IP-literal addresses so no DNS double is needed.
  Its payload check moves to `webhook`'s domain test.
- **`api/access/handlers_test.clj`.** The refusal, 404, `Location` and
  actor-naming tests go, the `access/` scenarios covering them, after new
  scenarios for `:invitation/already-exists` and
  `:membership/already-exists`. The command-payload tests stay.
- **`api/bank/person_create_test.clj`.** Goes,
  `access/person-creates-second-bank.edn` covering it.
- **`api/cash_account/queries_test.clj`.** Goes after a
  `cash-accounts/get-not-found.edn` scenario.
- **`api/bank/commands_test.clj`, `api/auth_test.clj` and
  `api/oauth/handlers_test.clj`.** The cases `me/`, `auth/`, `access/`
  and `oauth/` scenarios repeat go; the store-failure 503 and the pure
  data tests stay.

### The domain runner

**Verb kinds.** Every verb in `verbs.clj` is declared with one kind in a
`verb-kinds` map in `scenario.clj`, which replaces `assertion-verbs`:

- `:model`: a command the model has a spec of the same name for.
- `:fixture`: a write beneath the domain that sets up a state the domain
  then reacts to, and that the model mirrors. `:apply-fee` and
  `:fund-house` become `:fixture/apply-fee` and `:fixture/fund-house`.
- `:reality`: a production path the model has no rule for.
- `:read` and `:assert`: change no state, and never stop a comparison.

`:outbound-transfer` is retired in favour of `:outbound-payment`, which
goes through production. `:activate-party` and `:settle-outbound-payment`
are removed from the model and the runner.

**Compared or reality-only.** A scenario carries `:model :compared`, the
default, or `:model :reality`. A compared scenario naming a `:reality`
verb is refused at load with `:test-scenarios/scenario`, naming the verb
and the file. A compared scenario is compared after every step from first
to last.

**Shape.** `:given` holds `:model` and `:fixture` steps, `:when` the steps
under test, and `:then` only `:read` and `:assert` steps. Each verb's
arguments have a closed Malli schema in `scenario.clj`, and the whole
corpus is validated before the first scenario runs.

**Divergence.** `divergence.clj` walks a failing sequence one step at a
time, projecting after each, and returns the first step where the model
and the system differ, as
[ADR-0009](../adr/0009-model-equality-property-testing.md) promises. Both
the EDN runner and the property test report it.

**Invariants in the property.** `invariants/check` returns failures as
data. The property test treats any failure as a false trial, so fugato
shrinks it; the EDN runner asserts the same data with `is`.

**Waiting.** `quiescence.clj` goes. A verb that submits through the
pipeline waits for the record it wrote through `await.clj`, one helper
with one timeout. A timeout is recorded as `:timed-out` and fails the
scenario as a runner error, never as a rejection the model would accept.

**The model and projections.** `:create-bank` takes a low `:freq`.
`test-projections` gains `project-interest`, comparing each account's
accrued interest and carry with the model's.

**The corpus.** EDN files move into the API corpus's capability
directories. Tags go. Files repeating an API scenario go: the full happy
path, held release and return, outbound return, admission, migration
commit, the forced job run, the product-count cap and the not-found
kinds. Reality-only files the API cannot reach stay: the dead-lettered
settlement, a provider event delivered twice, provider balance
mirroring, a redelivered submit, and the intent and scheme-command
assertions. `closed-control-refuses-a-posting-test` moves here from the
API test namespace as a reality-only scenario, on a new
`:close-ledger-account` verb.

### API scenario fixtures

A fixture is a named, parameterised block of steps in
`test-resources/test-api-scenarios/fixtures/<name>.edn`:

```clojure
{:doc "A bank on the run's providers, with its token."
 :params {:name "Acme Bank" :tier "micro" :currencies ["GBP"]}
 :steps
 [{:command :api/request
   :request {:method :post :path "/v1/banks"
             :body {:name [:param :name] :status "live"
                    :tier [:param :tier] :currencies [:param :currencies]
                    :providers [:run :providers]}}
   :assert {:status 201}
   :as :bank}]}
```

A scenario step names one, its parameters and an alias:

```clojure
{:fixture :funded-account :as :acme :with {:amount 250000}}
```

`scenario.clj` expands fixtures at load, so the runner sees only steps.
A fixture may use another. Its captures are reached through the
instance's alias, `[:ref :acme :account :cash-account-id]`, so two
instances never collide. The first set is `:bank`, `:product`,
`:person`, `:account` and `:funded-account`, each the setup the corpus
repeats most.

### The API scenario shape

**Sections.** `:given` holds fixtures and requests whose assertions are
status only, `:when` the steps under test, and `:then` reads, polls,
mail and assertions, never a write. A scenario's `:name` is its
`testing` label.

**Schema.** Each verb has a closed schema in `scenario.clj`. `:assert`
means one thing, `{:status :body :headers :problem}`, on `:api/request`
and `:api/poll`'s `:until`; `:api/race` adds `:fresh`. `:assert/status`,
the unused context `:counter` and the unused markers go.

**Explicit effects.** Each moves out of the verb that hid it:

- The token a bank create mints is the `:bank` fixture's, captured as
  `[:ref <alias> :token]`; `:auth/mint-token` stays for a second token.
- Identity verification runs in the `:person` fixture, on a `:verify`
  parameter, and a bare `POST /v1/parties` verifies nothing.
- A lost reply is declared on its step, `:fault :lost-reply`, in place of
  the key prefix.

**Idempotency keys.** A write with no `Idempotency-Key` header is given
one derived from the run, the file and the step. A literal is written
only where a scenario proves a replay, and the uniqueness lint keeps
holding literals.

**Layout and names.** Directories follow the PRDs: `access/`,
`onboarding/` (banks, auth, OAuth and `me`), `parties/` (payee checks
included), `cash-account-products/` (rewards included),
`cash-accounts/` (migrations included), `payments/`, `interest/`,
`policies/`, `webhooks/` and `platform/` (the end-to-end journey,
providers, simulate and jobs). A file is named for the behaviour, a
refusal ending `-refused` and a missing resource `-not-found`; `-happy`
goes.

**Tags.** One closed vocabulary: `:serial`. Provider capabilities move to
`:requires`.

### Provider runs

A scenario declares the providers it runs on: `:runs-on {:payment
:every}`, `{:idv :every}`, or nothing for the defaults alone. `:requires`
names the capabilities it needs, checked against each provider's
declaration, and the rig's `application-test.yml` carries the
`unbuilt` capabilities under `test-api-scenarios/providers`. The run's
providers reach the bank create through the `:bank` fixture's
`[:run :providers]`, so `for-run`'s path match goes. A skipped run is
logged with its reason and counted in the run's summary, and reports no
`testing` block.

### Waiting

`await.clj` is the one loop: it re-runs a step until its assertion holds
or a deadline passes, and `:api/poll`, `:mail/await-invitation`,
`:idv/verify` and the invariants' settle all use it. The timeout is one
config key, `await-timeout-ms`, in the rig's YAML. `:wait` stays for a
token's expiry only; `platform/full-happy-path.edn`'s fixed wait becomes
polls.

The receiver verb, the equality assertion and the delivery wait
[webhooks.md](webhooks.md) slice 2 needs are built on `await.clj`: a
receiver records the requests it is handed, `:receiver/await` waits on
them, and `:assert/equals` holds a captured body equal to a read
route's response with no markers.

### Running concurrently

`api-scenarios-test` runs scenarios on a pool of `workers` threads, a
config key set from `!env TEST_API_SCENARIO_WORKERS` with a default of
4, each task wrapped in `bound-fn` so `clojure.test`'s counters and
contexts carry. `:serial` scenarios run after the pool drains, one at a
time, then the span assertions run. `fault/reset-lost!` runs once,
before the pool.

Two clashes are removed first:

- **Shared verification emails.** The Zyphe simulator resumes a pending
  run for the same person, as Zyphe does, and every automatic
  verification sends `person@example.test`. The `:person` fixture sends
  an address unique to the run and the step, and the files sharing
  `ford@example.test` take an address each.
- **The global refusal.** `POST /simulate/open-refused` refuses the next
  account any bank opens. `cash-accounts/open-refused.edn` is `:serial`.

`onboarding/bank-list-owners.edn`, which reads the newest-first first
page of every bank, is `:serial` too.

The namespace carries no `^:eftest/synchronized`, so its tests run
beside each other. The literal lint and the corpus schema move to
`corpus_test.clj`, which boots nothing.

### Documents

- [testing](../recipes/test/testing.md) states the tiers, the placement
  questions, what a brick test may do, and the semgrep rule in its
  Rules, and the `idioms` rule is synced from it.
- [service-apis.md](service-apis.md)'s Testing section, the matrix
  sentence in [bank-providers.md](bank-providers.md), the rig sentence in
  [scheduler.md](scheduler.md), and `CLAUDE.md` name the bricks and paths
  as they are.
- `docs/plan/scenario-testing.md` and `docs/tdd/bank-providers.md.bak`
  are deleted, and the `bank-test-*` names left in anomaly kinds and
  docstrings are renamed.

### Five slices

The API scenarios come first: their building blocks are built, and the
corpus that exists is moved onto them, before anything moves in from
another brick. Each slice ends with the dev project's tests green and
the CI time of its job recorded.

1. **The API runner's building blocks.** The closed schema and
   `corpus_test.clj` validating it; fixtures and their expansion;
   generated idempotency keys; the explicit token, verification and
   fault; provider declarations and reported skips; `await.clj`; and the
   pool with `bound-fn`, the serial pass and unique verification emails.
2. **The API corpus on them.** Every existing scenario moved to fixtures,
   the sections, the PRD directories, the names and the vocabulary,
   scripted, with every scenario on every provider it ran on before;
   `open-refused` and `bank-list-owners` serial; the happy path's fixed
   wait replaced by polls.
3. **Brick tests.** The new API scenarios for what only a brick or base
   test covers; then the semgrep rule, the scope check over bases, the
   moves and deletions above, the trimmed rigs, and the testing recipe
   and `idioms` rule.
4. **The domain runner.** Verb kinds and the closed schema; compared and
   reality-only scenarios; the fixture verbs and the retired ones;
   `divergence.clj`; invariants as data; `await.clj` and `:timed-out`;
   `:freq` and `project-interest`; the corpus moved, its duplicates
   deleted, and closed-control and the ledger close moved in.
5. **The remaining documents**, and this TDD renamed implemented.

### Tests

- **`test-api-scenarios`.** `corpus_test.clj` holds that every scenario
  and fixture validates, no `:then` writes, every fixture is used and
  every key literal is unique. `api-scenarios-test` runs every scenario
  on each provider it declares, reports each skip, and asserts the run's
  spans. `realm_test.clj` is unchanged.
- **`test-scenarios`.** Every compared scenario is compared after every
  step; a compared scenario naming a reality verb is refused at load; a
  broken invariant falsifies a property trial; a divergence names its
  first step; the property test runs on the weighted model.
- **`test-model`.** Each command's `:next-state` in isolation, without
  the removed commands.
- **`test-projections`.** Each projection pair over a hand-built model
  state, `project-interest` included.
- **The checks.** `just semgrep` and `enforce-idioms.sh --all` pass over
  the tree with no opt-out but those listed above.

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
- **Deleting every crossing brick test at once.** Rejected: some are the
  only coverage of a refusal, and move first.

## Known Limitations

- **PRD journeys with no scenario.** Webhook notifications, outages and
  IDV events; interest beyond the end-to-end journey; curative transfers
  and daily limits over HTTP; organisation parties; a customer's second
  currency; the payment refusals the domain corpus holds and the API
  does not; and routes no scenario calls, among them the policy and tier
  reads, a company lookup, a balance by type, a migration's cancel, a run
  by id, webhook test notifications and resends, and the discovery
  documents.
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
- [testing](../recipes/test/testing.md) — the placement rule, the scope
  check and the commands that run the tiers.
- [test-system](../recipes/test/test-system.md) — `with-test-system`,
  `nom-test>` and the runner's permits, mono's recipe.
- [chart-of-accounts.md](chart-of-accounts.md) — the two invariants
  both runners assert after every step.
- [bank-providers.md](bank-providers.md) — the providers a scenario runs
  on, and their declarations.
- [webhooks.md](webhooks.md) — the delivery scenarios built on the
  receiver verb and the await.
- [idempotency.md](idempotency.md) — the replay and race contract the
  API scenarios prove.
- [service-apis.md](service-apis.md) — the API surface the scenarios
  drive.
- [fugato](https://github.com/vouch-opensource/fugato) — the
  command-sequence generator and shrinker.
