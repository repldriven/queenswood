# Performance testing

> **Status: proposal.** k6 in the dev shell, the Modulr simulator, SigNoz in
> every cluster and a chart that installs on kind and on GKE exist, and
> Background names them. The Proposed Solution is the build list, of which
> the first three slices in [First slice](#first-slice) are built: the
> internal, outbound and inbound scenarios, the bank they run against and
> the Job that runs them on kind.

## Objective

Measure how many payments a second one bank can make on a Queenswood
installation, and how many customers a second it can onboard, and show
where the time goes when the rate stops rising. The
first run is a baseline: it is expected to be slow, and every later change
to the payment path is judged by the run that follows it. This design
decides the load scenarios, the bank they run against, where the load
generator runs, what a run reports, and the reference rates a result is
read against.

In scope: k6 scenarios for internal, outbound and inbound payments and
for onboarding customers on a bank whose payment provider is Modulr and
identity provider Zyphe, a policy tier the test bank is
placed on, k6 running as a Job inside the cluster on kind and on GKE, the
figures a run reports, and the ceilings the baseline is expected to find.

Out of scope: raising any ceiling a run finds, which the design of the
brick holding it decides — [payments](payments.md),
[transaction-processing](transaction-processing.md) and
[ADR-0036](../adr/0036-simulators-run-in-a-service-of-their-own.md); read load and
webhook delivery to a customer; the Form3 and ClearBank
providers, which [bank-providers](bank-providers.md) describes; and load
against a provider's own sandbox.

## Background

- **k6 is in the dev shell.** `pkgs.k6` is in
  [flake.nix](/flake.nix). Nothing in the tree uses it.
- **A payment's 201 waits for the processor's commit.**
  [commands.clj](/bases/api/src/com/repldriven/queenswood/api/payment/commands.clj)
  in `api` sends `submit-internal-payment` or `submit-outbound-payment`
  over the bus and waits on the reply in mono's command dispatcher, which
  gives up after the dispatcher's `timeout-ms`, 10 seconds, as
  `:command/timeout`.
  An internal payment's 201 means it settled. An outbound payment's 201
  means it was accepted and its amount reserved.
- **Payment commands run four at a time.** `topic-payments-command` and
  `topic-schemes-payments-event` have four partitions in
  [kafka-topics.yml](/components/resources/resources/system/kafka-topics.yml)
  and `financial-processors-service` four replicas, one partition of
  each per replica; every other topic has one, and each consumer handles
  one message before the next. The `:ordering-key` the API sends, the
  debtor account, decides the partition, so one account's payments stay
  in order, as
  [account-serialisation](../plan/account-serialisation.md) designs.
- **Onboarding commands and evidence run four at a time.**
  `topic-parties-command`, `topic-idvs-command` and `topic-idv-event`
  have four partitions and `operational-processors-service` four
  replicas. The API keys an existing party's commands and its
  verification sessions by the party, and each IDV adapter keys a
  verification's evidence by the verification. A create carries no key,
  as no earlier command names its party.
- **An internal payment writes only its two customer balances.** A
  control account's balance is the sum of its sub-ledger's, read from
  SUM indexes the Record Layer keeps by atomic mutation, so payments in
  one bank share no row on the control — see
  [ADR-0037](../adr/0037-a-control-accounts-balance-is-the-sum-of-the-balances-that-roll-into-it.md).
  An outbound submit still writes the 1200 pending-outbound balance and
  reads the bank's daily SUM index serializably in `payment-query`, and
  every settlement writes 1100.
- **An internal payment on a Modulr bank is a provider transfer too.**
  Modulr declares `balances: per-account` in
  [modulr.yml](/components/resources/resources/system/payment-providers/modulr.yml),
  so the payment's activity entry becomes a `ProviderTransfer` and a
  `transfer-between-accounts` command in
  [provider_transfer.clj](/components/payment/src/com/repldriven/queenswood/payment/events/provider_transfer.clj),
  which the adapter sends to Modulr as a payment between accounts.
- **An outbound payment reaches Modulr through two relays and a poller.**
  The submit records an activity entry; a `changelog-relay` runner per
  bank-activity shard in `exclusive-dispatchers-service`, polling every
  100 ms for up to 500 entries, publishes it; `payment`'s activity event
  processor sends the command; the Modulr adapter saves an intent; and
  the `intent-poller` in
  [core.clj](/components/intent-poller/src/com/repldriven/queenswood/intent_poller/core.clj)
  reads every pending and sent intent and makes the calls that may run at
  once on its `concurrency` workers, eight for each payment adapter. The
  simulator's webhook marks the intent settled and
  writes the outcome to `modulr-outbox`, whose relay publishes it for
  `payment` to settle the payment.
- **The Modulr simulator keeps a balance per account.** It runs in
  `external-simulators-service`, apart from the adapter. A payment its source
  cannot cover waits as `PENDING_FOR_FUNDS` and expires after
  `pending-for-funds-ms`, 30 seconds, with no notification. Money the
  platform credits through `POST /v1/simulate/inbound-transfer` reaches
  the simulator as a credit, so its balances follow the ledger's.
- **A test bank is built over the API.** An admin token from
  `POST /oauth/token` creates a bank with `POST /v1/banks`, whose response
  carries the bank's client id and secret once. A party starts pending
  and becomes active only after a verification session is decided at the
  IDV simulator; a cash account opens only for an active party and after
  a round trip to Modulr. `POST /v1/simulate/inbound-transfer`, refused
  for a bank not in `test` status, credits the bank's own-funds account,
  and internal payments from it fund the rest. The scenario fixtures in
  `test-api-scenarios` do each step.
- **Policies cap a bank's day.** The micro tier allows 50 cash accounts,
  500 internal and 500 outbound payments and 50 verification sessions a
  business day, and the platform policy, always applied, 100,000 of each
  kind of payment and 100,000 verification sessions, all under
  [policies](/components/resources/resources/policies). The business
  day ends at 17:00 London time, and a breach is a 429. Bootstrap seeds
  the `platform` and `micro` tiers, and only an admin names a bank's
  tier.
- **The other services run one replica.** The chart's
  [values.yaml](/infra/helm/queenswood/values.yaml) sets `replicas: 1`
  beside the two processor services' four, and
  `exclusive-dispatchers-service` stays at one by design.
  Each `api-service` process reads command replies in a consumer group
  of its own, from the newest on, so every replica hears its own.
- **Traces and JVM metrics go to SigNoz in the cluster.** Every service
  exports its traces and its JVM's runtime metrics over OTLP to the
  SigNoz the chart installs, as
  [ADR-0035](../adr/0035-traces-and-jvm-metrics-go-to-signoz-in-the-cluster-that-produces-them.md)
  decides, and `queenswood-jvm` sets them side by side per service.
- **The kind loop installs the deployed chart.** `just kind-up` installs
  [values-dev.yaml](/infra/helm/queenswood/values-dev.yaml) and
  [values-local.yaml](/infra/helm/queenswood/values-local.yaml) on kind
  with images from the local registry, one FDB storage process and one
  Kafka broker, inside Colima's 9 CPUs. The API is reached from the host
  by `kubectl port-forward`.
- **Another bank's payment arrives through the simulator.**
  `POST /simulate/inbound-payment` on the Modulr simulator takes a BBAN,
  an amount in pounds and a debtor name, answers 202, and credits the
  account it holds under that BBAN, telling the adapter with a PAYIN, as
  a Faster Payment from another bank would. Inbound payments are listed
  only by status, so one cannot be found by the id the simulator answers.

## Proposed Solution

### Reference rates

A run's figures are read against what a bank of a given size makes at its
peak. At about 0.3 transfers a customer a day, a bank of 3 million
customers makes around a million payments a day, 12 a second on average,
and 40 to 60 a second at a payday peak of three to five times that; a bank
of 100 million makes around 2,000 a second at its peak. Three readings
follow from them:

- **Challenger.** 50 payments a second sustained for an hour, then 150 a
  second for five minutes, with internal and outbound mixed.
- **Knee.** The rate at which the 99th percentile or the error rate
  breaks, with the cluster's node sizes beside it.
- **Scaling.** Payments a second against replicas, partitions and shards,
  toward 2,000, naming each part that does not scale out.

A baseline run records each reading without failing on it: k6 thresholds
carry the challenger figures with `abortOnFail: false`, so a summary says
which were met.

### Load scenarios

Scripts live under `perf/`, outside every brick:

- `perf/lib/api.js` — tokens and their refresh before expiry, a fresh
  `Idempotency-Key` per request, and responses tagged by status.
- `perf/lib/bank.js` — the test bank, described below.
- `perf/lib/load.js` — a profile's steps, the options for them and the
  step a VU is in, which every scenario shares.
- `perf/lib/books.js` — the ledger and the provider's balances read at
  the end of a run, against the money setup injected.
- `perf/internal.js` — scenario A, every account paying a random other
  account, and scenario B, one account paying the rest at fixed steps.
- `perf/outbound.js` — scenario C, outbound payments from a random account
  to a sort code no member of the scheme holds, so the payment leaves the
  simulator and nothing comes back.
- `perf/inbound.js` — scenario D, payments from another bank sent through
  the simulator to a random account, the bank opening its accounts
  unfunded.
- `perf/mixed.js` — internal and outbound payments from one bank in one
  arrival rate, `OUTBOUND_SHARE` of them outbound, the challenger's mix.
- `perf/parties.js` — one customer an iteration into a bank with no
  customers: a person party created, a verification session opened and
  awaited ready, the Zyphe simulator's decision posted as the person after
  `THINK_S` seconds, none by default, and the party awaited active; with
  `OPEN_ACCOUNT=true`, a current account then opened and awaited opened
  at Modulr. A stage that refuses, or outlasts `STAGE_TIMEOUT_S`, 120 by
  default, loses the customer there.
- `perf/accounts.js` — one current account an iteration, opened for the
  next of the `PARTIES` setup verified, 50 by default, in turn; one in 20
  followed until it is opened at Modulr, and lost where it outlasts
  `OPEN_TIMEOUT_S`, 120 by default.
- `perf/reads.js` — one read an iteration of a random account that setup
  funded and gave `HISTORY` payments sent and received, 20 by default:
  the account with its balances embedded, 40%; the first page of its
  transactions, 40%; and its balances, 20%, each timed by its `name`.
- `perf/interest.js` — a bank's `daily-interest` job moved three minutes
  ahead and its run followed to the end, while internal payments between
  the same accounts run at a fixed rate. Setup opens and funds the
  accounts, at least £1,000 each, on a product paying `RATE_BPS`, 500 by
  default. The summary's `interest` block gives the run's start and end
  in seconds into the load, and each task's time, accounts processed and
  accounts failed; it checks no books, since capitalisation moves money
  into the customers' balances.

Every scenario runs as steps of a fixed arrival rate on k6's
`ramping-arrival-rate` executor, each reached over five seconds and then
held, so the rate sent does not fall when responses slow. `PROFILE` picks
the steps:

- **`smoke`.** 1 a second for a minute, on 10 accounts.
- **`challenger`.** 50 a second for an hour, then 150 for five minutes, on
  200 accounts.
- **`knee`.** 20, 40, 80, 160 and 320 a second, a minute each, on
  200 accounts, run to the end however many requests fail, since an
  aborted run skips the teardown that checks the books.
- **`hot`.** Scenario B: 1, 2, 5, 10, 20 and 40 a second, a minute each,
  from one account to the other 49, for internal payments only.

`perf/parties.js` defines its own: `smoke` at 1 a second for a minute,
`challenger` at 10 a second for ten minutes, and `knee` at 20, 40 and
80 a second, a minute each. `perf/accounts.js` keeps the payments'
`knee` with a `challenger` at 50 a second for ten minutes, and
`perf/reads.js` runs its `knee` at 80, 160, 320, 640, 1,280 and 2,560 a
second, with a `challenger` at 200 a second for ten minutes.
`perf/interest.js` has a `smoke` of 100 accounts at 5 a second for five
minutes and a `challenger` of 1,000 accounts at 50 a second for eight.

`FROM` drops a profile's steps below that rate, for a machine where
the low steps tell nothing. `RATE` and `DURATION` replace a profile's
steps with one-minute steps at that rate, so a sustained run shows
whether the rate holds, and `ACCOUNTS` replaces its account count.
Amounts are between 1p and £1, and funding covers the run twice over,
the hot account's every payment included, so no payment is refused for
its balance.

A rejection is counted by status: 429 is a policy cap, 503 is FDB
unavailable or in contention, 500 is a failure or the dispatcher's
10-second timeout, and 0 is no response at all.

### The test bank

`perf/lib/bank.js` builds a fresh bank in k6's `setup()` on every run, so
its daily counts start at zero:

1. Takes an operator token as the `queenswood-perf` client.
2. Creates a `test` bank on the `perf` tier with Modulr as its payment
   provider.
3. Takes the bank's own token, creates and publishes a current-account
   product.
4. Creates up to `PARTIES` parties, 50 by default, each decided as a
   match at the IDV simulator, and waits for each to be active.
5. Opens `ACCOUNTS` accounts, 200 by default, across those parties in
   turn, and waits for each to be opened.
6. Credits the own-funds account by `POST /v1/simulate/inbound-transfer`
   and pays each account its share from it.

Requests in `setup()` carry the tag `phase: setup`, and every threshold
filters on `phase: load`.

### A `perf` tier

`components/resources/resources/policies/perf/restricted/policy.yml`
declares a tier with no capabilities or limits of its own, so the platform
policy alone binds a bank on it, and `bootstrap-service`'s
[application.yml](/projects/bootstrap-service/resources/application.yml)
seeds it beside `micro`. Only an admin can place a bank on it, by naming
it at creation or through `POST /v1/bank/change-tier`.

### Running in the cluster

k6 runs as a Job in the `queenswood` namespace, on kind and on GKE alike,
and reaches `http://queenswood-api-service:8080` and the simulators by
their Services:

- **Image.** `grafana/k6` at the version the dev shell's `k6` reports,
  so the runner is the k6 that built the archive.
- **Scripts.** `k6 archive` bundles the scenario, its imports and its
  parameters into one tar, kept in the ConfigMap
  `queenswood-perf-scripts` and mounted at `/perf`. A run from an archive
  reads only the environment baked into it and what `-e` passes, so the
  Job passes its credentials as `-e` arguments.
- **Credentials.** The operator client the realm adds for load tests,
  read from the Secret `queenswood-keycloak-dev-operator`, described
  below.
- **Job.** [k6-job.yaml](/infra/perf/k6-job.yaml), rendered by
  `envsubst` with the image, and deleted before a new run.
- **Summary.** `handleSummary` prints the run's parameters, its headline
  figures and k6's data as JSON between markers on stdout, and the
  recipe writes it from the pod's log to
  `target/perf/<scenario>-<profile>-<time>.json`, beside the log.

[perf.just](/justfiles/perf.just) carries `perf-run scenario profile`,
which archives the scenario, replaces the ConfigMap, applies the Job,
follows its log and writes the summary, treating k6's exit 99 — a
threshold not met — as a finished run, and `perf-clean`, which deletes
the Job and the ConfigMap. On GKE the Job carries a node selector for a
pool the services do not use.

### The operator client

The committed realm's operator client, `queenswood-admin`, signs its
client assertions with a key bootstrap generates into
`queenswood-keycloak-admin`, which only the platform's services mount. A
load test signs in as a client of its own instead:

- **Realm.** `keycloak.dev.operator` in the chart's
  [values.yaml](/infra/helm/queenswood/values.yaml), a `clientId` and a
  `secret`, adds a client authenticating with that secret, its service
  account holding the `admin` realm role, to the realm the dev Keycloak
  imports. `queenswood.devRealmWithOperator` in `_helpers.tpl` adds it
  at render time, as `queenswood.devRealmWithUser` adds the `dev` user.
- **Secret.** The chart keeps the pair in
  `<release>-keycloak-dev-operator`, which the Job reads.
- **Scope.** [values-local.yaml](/infra/helm/queenswood/values-local.yaml)
  names `queenswood-perf`. A client id under any Keycloak mode but `dev`
  fails the render, so no deployed realm carries it.

### What a run reports

- **Rate.** Payments a second asked for and achieved, and k6's
  `dropped_iterations`, the payments it could not start because every VU
  was waiting.
- **Latency.** The average, p50, p95, p99 and the maximum of the submit.
- **By step.** Each step's rate asked and achieved, its latency and its
  failure rate, read from thresholds on the step's tag that nothing
  fails, since k6 summarises a tagged metric only where a threshold
  names it.
- **Rejections.** Counts by the statuses above.
- **Settlement.** One outbound or inbound payment in 20 is followed, its
  VU polling from 50 ms, half as long again each time up to a second, and
  recording the time from the submit as the `settle_time` trend. An
  outbound payment is followed by `GET /v1/payments/outbound/{payment-id}`
  until it is completed, failed or returned. A followed inbound payment
  goes to one of the first tenth of the accounts, which nothing else
  pays, and is followed by the account's posted balance until it has
  risen by the amount. The summary's `settlement` counts the followed,
  those never settled by why, and the trend; `gracefulStop`, 120
  seconds, lets the last of them settle, and its polls carry
  `phase: follow`, so no load figure counts them.
- **Onboarding.** The customers onboarded, those lost by stage, and the
  time of each stage awaited: the session ready, the party active from
  the decision, and with `OPEN_ACCOUNT` the account opened from its
  request, with the whole journey's time less the person's. Polls carry
  `phase: poll` and the decision `phase: simulator`, so the load figures
  are the requests a customer sends.
- **Books.** After a run, k6's `teardown` reads the bank's ledger through
  `GET /v1/ledger-accounts` and the Modulr simulator's balances for the
  bank's accounts until they stop moving: two readings five seconds
  apart that agree, with the provider matching the ledger. The summary's
  `books` block sets them against what the books should hold: the money
  setup injected from outside, plus what the run sent in and less what
  it paid out, which k6 counts as it sends, since outbound payments have
  no list to sum. 1100, the deposit and own-funds controls summed, and
  the provider's balances should each equal it, and the trial balance
  should tie. `perf-run` exits 1 when they do not, or when a run sent
  none of its unit, payments or customers. `perf/parties.js` moves no
  money, says so in its summary, and is not held to the books.
- **Where the time went.** The run's window in SigNoz, where a payment's
  trace crosses the API, the bus, the processor and the adapter.
- **Where FDB conflicted.** The storage dashboard's FoundationDB
  section: retries per second by category, from the
  `fdb.transaction.retries` counter, and the transactions that took more
  than one attempt by category and the keys FDB reported in conflict,
  from the `fdb-transaction` spans' `fdb.conflicting_keys`.

A published run's summary is committed under `perf/results/`, and its
headline added to the recipe's results.

### Expected ceilings

The baseline is expected to stop at the first of these, and each later
run at the next:

1. **The payment command consumer.** One partition and one message at a
   time: the achieved rate levels off at about one over the processor's
   time per command, latency rises with the queue behind it, and traces
   show the wait before the processor starts. Measured with the `knee`:
   about 51 a second at first, 64 once the producers stopped lingering,
   and, on two partitions with a 13.8 ms command, 78 a second clean at
   80 asked, with a p99 of 92 ms, and 103 to 109 a second at 160 and 320
   asked, where requests wait seconds and ceiling 2 takes over. On four
   partitions and four replicas the API accepts about 142 a second at
   160 and 320 asked: a command took 25 ms rather than 16 as every FDB
   step slowed by half together, on kind's one storage process. With FDB
   a process per role, a command takes 21.8 ms at 161 a second, all 160
   asked is accepted, and about 166 at 320, where the four replicas are
   88% busy and this ceiling is theirs again. That was on 12 CPUs; on 8,
   the size of a test instance, a command takes 25 ms and the API accepts
   about 127 a second at 160 and 320 asked, outbound 134 at 160, and an
   inbound is credited at 1.8 s at p50 rather than 0.7.
2. **Overload restarts the API.** Done. A request waited on its command
   until the dispatcher's 10-second timeout, holding one of Jetty's 50
   threads, so past the knee the pool filled, the liveness probe queued
   behind it and the pod was restarted. On virtual threads alone nothing
   capped the requests in hand and the 375 MB default heap ran out
   instead. The API now runs on virtual threads with mono's
   `max-in-flight` at 200, answering any request beyond it with a 503
   and `Retry-After: 1` before it reaches a route, and every JVM's heap
   is 60% of its container rather than the default 25%: at 75% the
   kernel killed a busy service for passing its limit. At 160 and 320 a
   second the API stays up, answers every request, accepts about 105 a
   second and turns the rest away in under a millisecond, with a p99 of
   about 4 seconds.
3. **One `api-service` replica.** Its own request handling. Replies no
   longer tie it to one: each process reads replies in a consumer group
   of its own from the newest one on, so every replica hears its own,
   and a restarted one does not wade through replies to requests that
   died with the last.
4. **The control balance.** Done for internal payments. With the
   payment command topic at two partitions and a replica on each, 176 of
   the 193 keys FDB reported conflicts on were 2100's balance row, a
   command took 45 ms rather than 19, and the API was restarted within
   two minutes. A control's balance is now summed from its sub-ledger by
   [ADR-0037](../adr/0037-a-control-accounts-balance-is-the-sum-of-the-balances-that-roll-into-it.md),
   and the same run holds 50 a second for ten minutes: 30,185 payments,
   none refused, 0.3% retried and only on creditor accounts, a command
   at 18 to 20 ms on each replica, and a p99 of 64 to 69 ms in the last
   three minutes. Done for outbound submits too: every submit credited
   1200's row and read the bank's daily outbound total serializably, and
   the ten-minute outbound challenger fell from 48 a second to 18, its
   submits retrying 3,546 times, 7,927 payments dropped and a followed
   payment settling in 85 seconds. With 1200 mirrored from the
   customers' pending-outgoing balances and the total read at snapshot,
   by
   [ADR-0038](../adr/0038-an-outbound-submit-writes-no-row-every-payment-shares.md),
   the same run holds 50 a second: 29,999 payments, none refused, ten
   retries in all, a p99 of 44 to 60 ms after the first minute, and
   every followed payment settled, at 0.7 seconds at p50 and 7.6 at
   p95. Settlements still share 1100.
5. **The intent poller.** Done for 50 a second. One thread made every
   call in turn, about 31 ms each, so through the ten-minute challenger
   the adapter was sent 50 transfers a second and completed 35 falling
   to 31, leaving about 8,000 pending at the end for five minutes, and a
   bank opening its accounts in that time waited behind them. Each
   adapter's poller now runs the calls that may run at once on eight
   workers, starts its next pass at once after one that made a call, and
   records an answer on the breaker only where it changes something. The
   same run completes 3,000 transfers a minute from the second minute to
   the last, each call 20 to 22 ms with a p99 under 40 ms, the workers
   busy for about one second in each, and nothing pending at the end.
   Each pass still reads every pending and sent intent.
6. **The relays.** One bank's activity is one shard's log, read by one
   runner at up to 500 entries every 100 ms.
7. **The outbound settlement consumer.** Done for 50 a second. `payment`
   settled outbound payments from `topic-schemes-payments-event`, one
   partition, one message at a time, at 17.6 ms each, so at 50 a second
   it was 89% busy and a burst queued: settlement took 0.7 seconds at
   p50 and 7.6 at p95, and 15.4 after a fresh cluster's first-minute
   stall. With 1100 summed from its legs by
   [ADR-0039](../adr/0039-cash-at-correspondents-balance-is-the-sum-of-its-legs.md),
   each payment adapter's events keyed by the payment, and the topic at
   two partitions, one per `financial-processors-service` replica, the
   same run holds 50 a second with none refused, each consumer 46 to 49%
   busy, and settlement at 0.45 seconds at p50, 0.79 at p95 and 2.7 at
   p99.
8. **The books hold.** In each ten-minute challenger 1100, the
   controls and the provider's balances end at what the books should
   hold, and the trial balance ties, within ten seconds of the load
   stopping: internal, 29,999 payments among 200 accounts, at the
   £60,400 setup injected; outbound, £15,067.35 paid out of £60,400,
   at £45,332.65; inbound, £15,154.58 sent in to accounts opened
   empty.
9. **Inbound payments.** The ten-minute inbound challenger holds 50 a
   second with none refused, the followed payments credited at 138 ms
   at p50 and 259 ms at p99.
10. **The books hold at the knee.** With the API shedding load and an
    outbound payment sent again under its Idempotency-Key until its
    outcome is known, the books hold to the penny at every `knee` step:
    internal at £76,800, outbound at £66,350.61 after 20,626 payments,
    and inbound at £18,791.21 after 37,229.
11. **Where each flow queues.** Read from each consumer group's lag past
    the knee: internal at `topic-payments-command`, the payment processor
    at about 105 a second on two partitions; outbound at
    `topic-bank-activity-event`, about 4,000 behind at 320 asked, and
    18,500 on four partitions, which let more payments in to wait there,
    one partition for the run's one bank; and inbound at
    `topic-schemes-payments-event`, about 12,000 behind at 312 a second on
    two partitions and 10,000 on four, a followed payment credited at
    1.1 s at p50 rather than 2.5. Webhook delivery on
    `topic-payments-event` is the first to fall behind, from 80 a second.
    An adapter's outbox waiting on its relay, about 180 messages a second
    to a runner, is behind no consumer group, so the lag does not show
    it.
12. **Onboarding.** One bank holds 20 customers a second with none lost,
    a request at 64 ms at p95. At 40 asked it reached 24 a second while
    a verification's three evidence events ran one at a time on one
    partition of `topic-idv-event`, and 34.5 once that topic had four:
    a create then waited seconds behind `topic-parties-command`'s one
    partition, at 33 ms a command. With the party and IDV command topics
    on four partitions, and a create reading the bank's effective
    policies through the stamped cache in two FDB transactions rather
    than four, it reaches 36.6 a second, the request at 1.3 s at p95 and
    a customer onboarded at 6.8 s, none lost. k6 ramps a step over five
    seconds, so 40 asked sends at most 39.2 a second, and dropped 153
    iterations it had no VU free for. The next queue is the webhook
    runner's consumer of `topic-idvs-event`, 1,565 behind: four events a
    customer at 6 ms each, on one partition in one
    `external-adapters-service` replica. With performers there, and the
    store opens of entry 14, 40 asked holds at a p99 of 59 ms rather than
    1.6 s, and 80 asked starts about 67 customers a second, every one
    onboarded, a session ready at 7.9 s at p95. Opening a session read
    the bank's effective policies and its IDV provider uncached; the IDV
    processor now keeps both, the provider for `cache-ttl-ms`, an hour by
    default.
13. **A consumer handles one message at a time.** Done. Each consumer
    now hands a message to one of its configured performers, chosen by
    the message's key, by
    [ADR-0041](../adr/0041-each-consumer-names-its-performers-and-the-key-that-chooses-them.md):
    four on the IDV, party and payment events the webhook runner reads,
    and on `topic-payments-command` and `topic-schemes-payments-event`.
    The webhook runner's lag on `topic-idvs-event` fell from 1,565 to 16,
    and onboarding reaches about 37 a second, k6's ramp capping 40 asked
    at 39.2.
14. **Opening a store.** Done. Every store a transaction opened read its
    header and read and rebuilt the whole record meta-data, so the first
    read of a store took 2.7 ms at p50 and a second read of it 0.4 ms. The
    `meta-store` now keeps the meta-data it last loaded with the
    database's meta-data version stamp it was loaded under, which a
    meta-data save bumps, and opens each store cacheable on open, its
    header kept by the Record Layer's store-state cache under the same
    stamp. The internal posting's transaction fell from 22.6 ms to
    15.4 ms at p50 in a one-a-second smoke.
15. **Reads issued one at a time.** Done. A posting's reads that need no
    other's answer are issued together and waited on once: the internal
    payment's two accounts, each leg's account balances, the records each
    save replaces, the outbound day's count and sum, and the ledger
    accounts the control check and `stored-legs` look up by code. Each
    payment processor keeps a ledger account's id by its code, bank and
    currency for `ledger-cache-ttl-ms`, 30 seconds by default, and reads
    the account by key so a closed one is still refused; a posting starts
    loading its control, 1100 and 1200 by those ids as soon as it knows
    its account. The internal posting's transaction fell to 13.3 ms, and
    the outbound settlement's from 15.3 ms to 11.5 ms, its four ledger
    lookups from 3.8 ms to 0.5 ms.
16. **Every flow at 160 a second.** With the three above, each `knee`
    holds 160 asked with none failed: internal at a p99 of 127 ms rather
    than 3.9 s, accepting about 257 a second at 320 asked and turning the
    rest away with 503; outbound submits at a p99 of 455 ms, 242 a second
    at 320; and inbound credits, counted from the
    `:payment/settle-inbound` transactions, keeping up with the
    transaction at 15 ms rather than falling behind at 72 to 96 ms, 298 a
    second at 320, and the books settled 5 seconds after the load rather
    than 51.
17. **Transactions per payment.** At 160 outbound a second every FDB
    transaction in the cluster slows together, the idempotency claim
    from 4.9 ms to 17 ms and the submit from 14 ms to 41 ms, at about
    1,170 transactions a second: an outbound payment costs about 13 of
    them across the services, its idempotency claim and save, the submit,
    the activity mirror, the Modulr adapter's finds, save, update and
    outbox, the webhook notification, the settlement and the reads
    following it. Fewer transactions a payment, not shorter ones, is
    what raises this ceiling.
18. **The bank activity processor.** Outbound settlement queues behind
    it: in an outbound `knee` run behind another's backlog, settlements
    fell from 82 a second to 42 while submits rose from 91 to 161, since
    every provider command for the run's one bank is sent from that
    bank's activity in order, one entry at a time, which a posting
    naming several accounts needs.

19. **Opening accounts.** One bank opened about 88 accounts a second,
    clean to 80 asked: the cash-account command consumer took one command
    at a time on one partition, about 8.5 ms each, while FDB's
    transactions barely slowed. An open is now sent under its bank and
    every other account command under its account, so the consumer's
    four performers take different banks' opens and different accounts'
    commands at once, while a bank's opens run in turn and its account
    limits, read serializably in one round trip, hold exactly without
    conflicting. The processor keeps a bank's effective policies in the
    stamped cache. One bank then opens about 120 a second, 80 asked at a
    p99 of 35 ms rather than 475, and its accounts open at the provider
    at the rate they are requested.
20. **Reads.** The `reads` `knee` holds 640 a second at a p99 of 19 ms
    and 1,280 at 192 ms, about 1,250 a second at the most, past which
    the API turns requests away with 503 at its 200 in hand. A page of
    an account's transactions loaded each leg's transaction one at a
    time; it loads them together, and 640 a second's p99 fell from 46 ms.
    Three more changes lift what the API serves at 2,560 asked:
    - **One transaction per read.** An account's balances and its page
      of transactions are each read in one transaction with the check
      that the account exists, which served about 1,380 a second.
    - **Type hints.** The `fdb` brick's Record Layer calls carried no
      type hints, so every load, scan and save looked its method up by
      reflection, about 16% of the API's CPU. Hinted, it served about
      1,530.
    - **mono v0.0.55.** A bearer token's signature is verified once and
      its claims kept until it expires. A span starts without walking
      the stack, and the header is parsed without a regular expression.
      Together these were about 10% of the CPU, and it served about
      1,630.

    1,280 a second holds with none refused at a p50 of 21 ms.
21. **Balances from legs.** A daily interest run over 1,001 accounts,
    with payments at 50 a second between them, failed 200: a
    capitalisation chunk of a hundred accounts held its transaction open
    for seconds, and payments to the same accounts exhausted its
    retries on their balance rows. A cash account's default buckets are
    now the sums of its legs, read from SUM indexes, so a posting appends
    its journal, and an account's sums are read serializably only where
    a limit bounds the way the posting moves them, as
    [ADR-0042](../adr/0042-a-cash-accounts-balance-is-the-sum-of-its-legs.md)
    decides.
    On a run over 10,001 accounts beside the same payments, accrual took
    9.8 s and capitalisation 24 s, 2.4 ms an account where it had taken
    13, no account failed, and the payments held a p99 of 67 ms or
    less. Outbound's `knee` holds 160 a second at a
    p99 of 279 ms rather than 455 and accepts 258 a second at 320
    rather than 242, internal's is unchanged, and reads serve about
    1,500 a second at 2,560 asked rather than 1,630: a read takes two
    range reads, the balance rows and the account's leg sums, where it
    took one.

### Span candidates

On kind, internal payments level off at about 51 a second on the `knee`
profile, and 50 a second does not hold for ten minutes: a queue forms
in the first minute, the API is restarted by its liveness probe, and the
achieved rate falls to between 18 and 35 a second. The spans of a run
below that, 4,522 payments at up to 40 a second, give the work each
payment costs, ranked by its effect on the serial command path, which is
`process-command` for `submit-internal-payment` in
`financial-processors-service` at 19.9 ms p50:

1. **The producer's `linger.ms`.** Done. Every `bus-send`, in every
   service, took 5.5 ms p50 and 5.9 ms p95: mono pins `kafka-clients`
   4.3.1, whose `linger.ms` defaults to 5 since Kafka 4.0, and its
   `kafka/send` waits on the send, so each one sat out the batching
   window. Every producer now sets `linger.ms: 0` beside `acks: all`, a
   send takes 0.4 ms, the serial path 15.2 ms, and the `knee` ceiling
   rose from about 51 a second to about 64, with 40 a second at a p99 of
   47 ms rather than 122. With the simulators in a service of their own,
   50 a second holds for ten minutes: 30,000 payments, none refused, at
   an average of 80 ms, a p50 of 37 ms and a p99 of 665 ms. The command
   consumer is 88 to 93% busy at that rate, so when the payment's
   transaction slows from about 33 ms to 55 ms at its slowest, a queue
   forms that takes 30 seconds to drain: the worst minute averaged
   270 ms.
2. **The payment's transaction.** A span around each step inside
   `:payment/submit-internal` splits its 18.7 ms: the bank's effective
   policies 4.9 ms, every platform policy among them read through the
   `Policy_by_label` index and decoded; `apply-legs` 3.3 ms, of which a
   second read of the platform tier was 0.8 ms; recording the
   transaction, its legs and its activity entry 3.1 ms; the control
   check 1.6 ms; the debtor account 1.3 ms; the daily count 1.2 ms; the
   rest under 0.6 ms each and the commit about 2 ms. The payment now
   passes the platform policies it holds to `apply-legs`, through
   `policy/platform-policies`, so the transaction takes 16.7 ms and a
   command 17.4 ms rather than 19.6. The payment processor then keeps a
   bank's effective policies in mono's `cache` for as long as the
   policy brick's stamp is unchanged: one versionstamped key, under the
   `fdb` brick's `stamp`, that every policy and binding write bumps, read
   at snapshot in place of the policies themselves. The read takes
   0.8 ms rather than 4.6, the transaction 13.0 ms and a command
   13.8 ms, and the challenger's minutes after the first average 23 to
   25 ms with a p99 of 39 to 47 ms.
3. **The relays' sends.** `exclusive-dispatchers-service` sends three
   messages per payment, one at a time, 16.6 ms of a runner's time, so
   one runner tops out at about 180 messages a second. A pass's sends
   could be made together and waited on once.
4. **The `transaction-posted` handler.** Done. The bank activity
   processor is one consumer per bank's shard, so its time per entry is
   a bank's ceiling however many replicas run: 15.4 ms, 77% busy at 50 a
   second, `:bank/get` twice and `:payment/mirror` at 10 ms. It now
   keeps a bank's provider, its 1100 and its own-funds account in mono's
   `cache`, since none changes once the bank exists, and takes 7.3 ms,
   36% busy, with `:payment/mirror` at 6.6 ms; the challenger's minutes
   after the first average 30 to 34 ms with a p99 of 60 to 86 ms.
5. **The Modulr transfer.** Four transactions under the
   `modulr-outbound` span: `:modulr-outbound/find` twice, the debtor's
   and the creditor's provider account read one after the other by
   `held-at`, then `:modulr-outbound/update`. The breaker is recorded
   only after a failure or where a pass found failures counted, as
   [ADR-0034](../adr/0034-outbound-calls-go-through-a-breaker-on-their-destination.md)
   has it. Two more `find`s per payment run outside the transfer.
6. **The API's idempotency.** `:idempotency/claim-or-replay` and
   `:idempotency/save`, two transactions and about 6 ms of each
   request's latency, off the serial path.

### First slice

1. `perf/lib`, scenario A at the `smoke` profile, the `perf` tier, the
   operator client, `infra/perf/k6-job.yaml` and `perf.just`, proved by
   a smoke run on kind.
2. The `challenger` and `knee` profiles and scenario B, run on kind to
   find the first ceiling.
3. Scenarios C and D with settlement sampling.
4. A node pool for the Job on GKE, the first published run, and
   `docs/recipes/test/performance-testing.md` with its results.

Each ceiling a run confirms is raised under the design of the brick that
holds it, and the run repeated.

### Tests

- **`bootstrap-service`.** `test-startup` in `just test-all` loads the
  production configuration with the `perf` tier seeded.
- **Chart.** `just helm-validate` renders the dev bundle with the
  operator client, and a render with it under another Keycloak mode
  fails.
- **`perf/`.** The `smoke` profile on kind runs the bank's setup and each
  scenario end to end, and is run before every published run.

## Alternatives Considered

- **Schemathesis.** Rejected: it fuzzes each operation for contract
  faults a few requests at a time, with no arrival rate to hold.
- **k6 on the host through `kubectl port-forward`.** Rejected: the tunnel
  through the Kubernetes API server caps the rate first and becomes what
  the run measures.
- **The monolith.** Rejected: one JVM on the local bus has none of the
  Kafka topics, service groups or hops a deployment has.
- **A fixed number of VUs.** Rejected: a VU waits for its response before
  sending again, so a slowing system is sent less and its queue never
  forms.
- **Form3 or ClearBank as the provider.** Rejected: neither is expected
  to be available to an installation, and their simulators differ from
  Modulr's by a call per payment rather than in kind.
- **Modulr's sandbox.** Rejected: a sandbox is rate-limited and measures
  the provider.
- **A k6 image of our own.** Rejected: no xk6 extension is needed, and a
  script changes without an image build.
- **The Job signing as `queenswood-admin`.** Rejected: it would hand a
  test Job the key every service authenticates as the operator with.
- **A separate realm for kind.** Rejected: the services would then sign
  in differently on kind from a deployment, and the dev bundle already
  adds what a local cluster needs to the committed realm at render
  time.
- **The k6 operator.** Rejected: one k6 pod sends thousands of requests a
  second, beyond the 2,000 reference.
- **Several banks sharing the load.** Taken in part, for scaling runs
  past one bank's ceiling. Rejected for the headline: a challenger is one
  bank, and splitting the load splits the contention it has.
- **Raising the micro tier's caps.** Rejected: they exist for production
  banks, and a `perf` tier leaves them alone.

## Known Limitations

- **Kind figures are not published.** k6 shares Colima's 8 CPUs and
  32 GiB, about a test instance's two `n2-standard-4` nodes, with what it
  measures, against one Kafka broker.
  [values-local.yaml](/infra/helm/queenswood/values-local.yaml) runs
  FDB as a process per role — three storage, two logs, and six
  stateless for two commit proxies, a GRV proxy, a resolver, the master
  and the ratekeeper — each with a 2Gi limit and `cache_memory` and
  `memory` below it, since its defaults outgrow the chart's 1Gi under a
  sustained run and the kernel kills the storage server. A deployed
  instance runs the chart's default of one storage process and one log
  with no stateless processes of their own, the layout kind ran until
  its knee showed every FDB step slowing together.
- **The platform cap ends a long run.** 100,000 payments of a kind in a
  business day is refused with 429, which 50 a second reaches in 33
  minutes; the cap is raised when a run nears it.
- **The provider transfer backlog is not measured.** An internal payment
  on a Modulr bank queues a transfer no API reports, and a failed one is
  only logged; traces show the queue.
- **A deployed instance has no load-test operator.** Its realm carries
  no `queenswood-perf` client, so a run on GKE needs an operator
  credential of its own, which the first published run decides.
- **A partition count is fixed when its topic is created.** mono's
  `kafka/topics` creates a missing topic and leaves an existing one
  alone, so a count raised in `kafka-topics.yml` reaches a cluster only
  once its topics are recreated, losing their messages and consumer
  offsets, or grown with `kafka-topics.sh --alter`, which keeps them but
  sends a key's later messages to another partition than its earlier
  ones, so the topic is drained first and its consumers restarted to
  take the new partitions. The topics come from the images' own
  `kafka-topics.yml`, so on kind a changed count needs
  `docker-build-all` before `kind-up`.
- **Kind stalls for a second or two.** In the two-partition challenger a
  few commands in six 20-second windows took 1.2 to 2.3 s while the
  average stayed near 20 ms, and the API's own FDB transactions slowed
  in the same windows, so the cluster rather than a conflict paused.
  Kind then ran one FDB storage process, which also held the commit
  proxy, the master and the ratekeeper, on Colima's disk beside k6 and
  every JVM.
- **A gone API process leaves its reply groups behind.** Each
  `api-service` process reads replies in groups named for it, so after a
  restart the old groups' lag grows on SigNoz's lag panel though nothing
  waits on them, until Kafka expires their offsets.
- **The processors' replicas are not evenly loaded.** Every other topic
  the financial and operational processors read has one partition, so
  one replica of each takes all of them beside its share of the
  partitioned ones.
- **The inbound `knee` measures injections.** Its steps are the
  simulator accepting a payment, under a millisecond, while the platform
  credits it afterwards, so a step's achieved rate says nothing of the
  credits. They are counted from the `:payment/settle-inbound`
  transactions in SigNoz until the summary reports them by step.
- **Knees run back to back share a backlog.** A run's provider
  transfers drain through the bank activity processor after its load
  stops, so the next run's settlements wait behind them; a pause of a
  few minutes between runs keeps them apart.
- **Kind's Kafka loses its topics on a restart.** The broker has no
  volume, so a restarted container starts empty and the services
  recreate each topic with one partition. The `apache/kafka` image's
  default 1 GiB heap filled the container's 1 GiB limit, and the broker
  was killed under load; `kafka.heapOpts` gives it 512 MiB. After a
  restart, a topic `kafka-topics.yml` declares with four partitions is
  grown back with `kafka-topics.sh --alter` and its consumers restarted.
- **Webhooks are absent.** The test bank registers no webhook endpoint,
  so no run measures a notification's delivery.
- **Reads and writes share one in-flight limit.** The API turns a request
  away with 503 once 200 are in hand, whatever they are, so commands
  waiting on their replies can fill it and refuse a read that would take
  2 ms.
- **The Modulr simulator forgets on a restart.** It holds what it has
  opened in memory, so after a restart it serves an account it issued
  earlier as active with nothing to check. It issues account numbers in
  turn from its start time's milliseconds, which stay ahead of any rate
  a run opens accounts at, so a restart issues no number an earlier
  simulator did until they wrap, about every 28 hours.
- **A Modulr call can be refused as a duplicate.** During the knees the
  simulator refused a few transfers with "The nonce has been used
  before". A nonce is 64 random bits, and the adapter marks a call a
  retry only from its intent's attempts, so a call whose intent update
  then lost to a conflict is likely sent again under the same nonce,
  unmarked.

## References

- [payments](../prd/payments.md) — the payments whose rate this measures.
- [payments](payments.md) — the internal and outbound paths and the
  Modulr adapter a run exercises.
- [transaction-processing](transaction-processing.md) — the line between
  one FDB transaction and the relays, which the ceilings follow.
- [bank-providers](bank-providers.md) — a bank's choice of Modulr, and
  the per-account balances that make an internal payment a transfer.
- [policy-evaluation](policy-evaluation.md) — how the platform and tier
  policies combine, and the caps a run must stay under.
- [scenario-testing](scenario-testing.md) — the correctness tiers, which
  a load run does not replace.
- [ADR-0036](../adr/0036-simulators-run-in-a-service-of-their-own.md) — the service groups and
  the one-replica dispatchers.
- [ADR-0021](../adr/0021-changelog-relay.md) — the changelog relay.
- [ADR-0035](../adr/0035-traces-and-jvm-metrics-go-to-signoz-in-the-cluster-that-produces-them.md)
  — SigNoz in the cluster, where a run's traces and JVM metrics go.
- [ADR-0033](../adr/0033-operations-reach-a-provider-in-the-order-they-were-accepted.md)
  — the activity log an outbound payment travels.
- [account-serialisation](../plan/account-serialisation.md) — the
  one-partition command topics.
- [deployment](../recipes/infra/deployment.md) — the kind loop and the
  chart.
- [k6 scenarios](https://grafana.com/docs/k6/latest/using-k6/scenarios/)
  — executors, and the open model the arrival-rate ones give.
