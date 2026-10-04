# Performance testing

> **Status: proposal.** k6 in the dev shell, the Modulr simulator, SigNoz in
> every cluster and a chart that installs on kind and on GKE exist, and
> Background names them. The Proposed Solution is the build list, of which
> the first three slices in [First slice](#first-slice) are built: the
> internal, outbound and inbound scenarios, the bank they run against and
> the Job that runs them on kind.

## Objective

Measure how many payments a second one bank can make on a Queenswood
installation, and show where the time goes when the rate stops rising. The
first run is a baseline: it is expected to be slow, and every later change
to the payment path is judged by the run that follows it. This design
decides the load scenarios, the bank they run against, where the load
generator runs, what a run reports, and the reference rates a result is
read against.

In scope: k6 scenarios for internal, outbound and inbound payments on a
bank whose payment provider is Modulr, a policy tier the test bank is
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
  gives up after 10 seconds as `:command/timeout`.
  An internal payment's 201 means it settled. An outbound payment's 201
  means it was accepted and its amount reserved.
- **Payment commands run two at a time.** `topic-payments-command` has
  two partitions in
  [kafka-topics.yml](/components/resources/resources/system/kafka-topics.yml)
  and `financial-processors-service` two replicas, one partition each;
  every other topic has one, and each consumer handles one message before
  the next. The `:ordering-key` the API sends, the debtor account,
  decides the partition, so one account's payments stay in order, as
  [account-serialisation](../plan/account-serialisation.md) designs.
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
  kind of payment and 100 verification sessions, all under
  [policies](/components/resources/resources/policies). The business
  day ends at 17:00 London time, and a breach is a 429. Bootstrap seeds
  the `platform` and `micro` tiers, and only an admin names a bank's
  tier.
- **Every service runs one replica.** The chart's
  [values.yaml](/infra/helm/queenswood/values.yaml) sets `replicas: 1`
  throughout, and `exclusive-dispatchers-service` stays at one by design.
  The API's command replies arrive on a one-partition topic under a fixed
  consumer group, so a second `api-service` replica would not see the
  replies to its own requests.
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
- `perf/internal.js` — scenario A, every account paying a random other
  account, and scenario B, one account paying the rest at fixed steps.
- `perf/outbound.js` — scenario C, outbound payments from a random account
  to a sort code no member of the scheme holds, so the payment leaves the
  simulator and nothing comes back.
- `perf/inbound.js` — scenario D, payments from another bank sent through
  the simulator to a random account, the bank opening its accounts
  unfunded.

Every scenario runs as steps of a fixed arrival rate on k6's
`ramping-arrival-rate` executor, each reached over five seconds and then
held, so the rate sent does not fall when responses slow. `PROFILE` picks
the steps:

- **`smoke`.** 1 a second for a minute, on 10 accounts.
- **`challenger`.** 50 a second for an hour, then 150 for five minutes, on
  200 accounts.
- **`knee`.** 5, 10, 20, 40, 80, 160 and 320 a second, a minute each, on
  200 accounts, aborting once more than 5 per cent of requests fail.
- **`hot`.** Scenario B: 1, 2, 5, 10, 20 and 40 a second, a minute each,
  from one account to the other 49, for internal payments only.

`RATE` and `DURATION` replace a profile's steps with one-minute steps at
that rate, so a sustained run shows whether the rate holds, and
`ACCOUNTS` replaces its account count. Amounts are between 1p and £1, and
funding covers the run twice over, the hot account's every payment
included, so no payment is refused for its balance.

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
   match at the IDV simulator, and waits for each to be active: the
   platform allows a bank 100 verification sessions a day.
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
- **Where the time went.** The run's window in SigNoz, where a payment's
  trace crosses the API, the bus, the processor and the adapter.

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
   asked, where requests wait seconds and ceiling 2 takes over.
2. **Overload restarts the API.** Nothing sheds load: a request waits
   in the queue until the dispatcher's 10-second timeout, and the API's
   liveness probe, with a 1-second timeout, fails behind the same queue,
   so Kubernetes restarts the pod and every request in flight fails to
   connect, where a 429 or 503 would have told the caller to back off.
3. **One `api-service` replica.** Its own request handling, until the
   reply topic is partitioned or replies are routed to the replica that
   asked.
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
7. **The outbound settlement consumer.** `payment` settles outbound
   payments from `topic-schemes-payments-event`, one partition, one
   message at a time, at 17.6 ms each, so at 50 a second it is 89% busy
   and a burst queues, which is the settlement tail. A second partition
   needs 1100 off the settlement's shared rows first, as 1200 is off
   the submit's.
8. **Inbound payments.** The ten-minute inbound challenger holds 50 a
   second with none refused, the followed payments credited at 138 ms
   at p50 and 259 ms at p99.

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

- **Kind figures are not published.** k6 shares Colima's CPUs with what
  it measures, against one FDB storage process and one Kafka broker.
  [values-local.yaml](/infra/helm/queenswood/values-local.yaml) gives
  FDB a 2Gi limit with `cache_memory` and `memory` below it, since its
  defaults outgrow the chart's 1Gi under a sustained run and the kernel
  kills the storage server.
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
  offsets.
- **Kind stalls for a second or two.** In the two-partition challenger a
  few commands in six 20-second windows took 1.2 to 2.3 s while the
  average stayed near 20 ms, and the API's own FDB transactions slowed
  in the same windows, so the cluster rather than a conflict paused.
  Kind runs one FDB storage process, which also holds the commit proxy,
  the master and the ratekeeper, on Colima's disk beside k6 and every
  JVM.
- **The financial processors' replicas are not evenly loaded.** Every
  other topic their consumers read has one partition, so one replica
  takes all of them beside its half of the payment commands.
- **Reads and webhooks are absent.** No scenario reads balances or lists
  payments, and the test bank registers no webhook endpoint.

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
