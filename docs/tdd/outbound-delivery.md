# Outbound delivery

> **Status: implemented.**

## Objective

Carry every outbound call through one delivery policy and a circuit
breaker on its destination, as
[ADR-0034](../adr/0034-outbound-calls-go-through-a-breaker-on-their-destination.md)
decides, so an outage at a payment or identity verification provider, a
customer's webhook endpoint or the mail server stops the calls to it,
costs the items queued behind it time rather than attempts, and ends
without a person stepping in.

In scope: the breaker and its FDB record; the delivery policy and its
configuration; the intent poller, the webhook runner and the email
runner taking both; and the webhook pause rule's removal.

Out of scope: what each loop sends and how it reads an answer, which
[payments](payments.md), [webhooks](webhooks.md),
[outbound-email](outbound-email.md) and
[transaction-processing](transaction-processing.md) decide; the order
intents reach a provider in, which
[ADR-0033](../adr/0033-operations-reach-a-provider-in-the-order-they-were-accepted.md)
decides; a probe of customers' banks' health, and a console view of
breakers, each a design of its own.

## Background

- **The intent poller.**
  [intent-poller](/components/intent-poller/src/com/repldriven/queenswood/intent_poller/core.clj)
  makes each due pending intent's call for an external adapter that has
  registered its operations with `defoperations`, one runner per
  adapter.
- **The webhook runner.**
  [outbound.clj](/components/webhook/src/com/repldriven/queenswood/webhook/outbound.clj)
  claims due deliveries under a lease, a bounded number in flight per
  endpoint, and POSTs each to the customer's endpoint.
- **The email runner.**
  [outbound.clj](/components/email/src/com/repldriven/queenswood/email/outbound.clj)
  claims due deliveries under a lease and sends each through the mail
  server.
- **The synchronous calls.** A Companies House lookup and a Confirmation
  of Payee check, each made through an external adapter while a request
  waits.
- **The registrars.** The ClearBank, Form3, Modulr and Onfido adapters
  each subscribe to their provider's notifications at start-up, and
  check the subscriptions are still held, so a simulator that restarted
  and forgot them is told again.

## Solution

### The breaker

A `circuit-breaker` component holds the breaker as pure functions over a
record and its FDB store:

- **States.** `closed` lets calls through and counts consecutive
  failures; at `failure-threshold` it opens. `open` lets none through
  until `retry-at`. Past `retry-at` the next call is the probe, made in
  `half-open`; its success closes the breaker and resets the count, its
  failure reopens it with the cool-down doubled, up to
  `max-cool-down-ms`.
- **What counts.** A call's outcome is `:answered`, a refusal included,
  since the destination answered it, or `:failed`, where it did not
  answer or answered with a 5xx, a 408 or a 429. Only `:failed` counts.
  A customer's endpoint answering anything but a 2xx counts.
- **The record.** `CircuitBreaker` in a new
  `schemas/outbound/circuit-breaker.proto`, keyed by `destination`, with
  `state`, `consecutive_failures`, `opened_at`, `retry_at`,
  `cool_down_ms`, `probe_claimed_by` and `probe_lease_expires_at`, in a
  `circuit-breakers` store with the meta-data version bumped and the
  store's `since` set, per
  [schema-evolution](../recipes/code/schema-evolution.md).
- **Writes.** A failed call increments the count, and a transition
  writes the new state; a success writes only where it closes the
  breaker or resets a count above zero. A probe is claimed in one
  transaction under a lease, so of two replicas past `retry-at` one
  probes and the other waits.
- **Destinations.** `adapter:<adapter>` for an external adapter's API,
  `webhook-endpoint:<bank-id>:<endpoint-id>` for a customer's endpoint,
  and `smtp` for the mail server.
- **Operations.** `circuit-breaker/allow` returns `:closed`, `:probe` or
  `:open` for a destination at a moment, claiming the probe for
  `probe-lease-ms` where it returns `:probe`; `circuit-breaker/record`
  takes the call's outcome, `:answered` or `:failed`; and
  `circuit-breaker/breaker` reads the record. Neither throws; a store
  failure is an anomaly the loop logs, and the loop calls as though
  closed.

### The delivery policy

A loop's `delivery-policy` names its numbers. Its schema is
`circuit-breaker/delivery-policy-schema`, which a runner's
`:system/config-schema` checks at start-up, refusing a policy missing a
key. The payment adapters' runners include
[payment-delivery-policy.yml](/components/resources/resources/system/payment-delivery-policy.yml)
and the IDV adapters'
[idv-delivery-policy.yml](/components/resources/resources/system/idv-delivery-policy.yml):

```yaml
default:
  initial-backoff-ms: 1000
  backoff-growth: 2
  max-backoff-ms: 60000
  max-attempts: 20
  max-age-ms: 3600000
operations:
  open-account:
    max-age-ms: 86400000
  close-account:
    max-age-ms: 86400000
  reissue-address:
    max-age-ms: 86400000
breaker:
  failure-threshold: 5
  cool-down-ms: 30000
  max-cool-down-ms: 600000
  probe-lease-ms: 30000
```

`operations` is the intent poller's: each entry overrides `default` for
that operation, and an operation with no entry takes `default`. The
webhook and email runners have no operations.
`circuit-breaker/retry-policy` returns the policy for an operation, and
`circuit-breaker/backoff-ms` and `circuit-breaker/give-up?` read it. An
item gives up when its own attempts reach `max-attempts` or it is older
than `max-age-ms`. The `test` profile shortens the backoff, the attempt
cap and the threshold, so a scenario's outage plays out in seconds.
Each runner's other numbers — `poll-ms`, and the claiming runners'
`batch-size` and `claim-lease-ms` — sit beside its `delivery-policy`,
required by the same schema.

### The intent poller

In
[core.clj](/components/intent-poller/src/com/repldriven/queenswood/intent_poller/core.clj),
a pass asks `allow` for `adapter:<adapter>` before it drains. `:open`
makes no call and counts no attempt; `:probe` makes one call, the first
the drain order would make; and `:closed` calls until a failure opens
the breaker, when the rest of the pass waits. An operation's `:call`
returns `[:answered result]` or `[:refused reason]`, recorded as
`:answered`, `[:retry reason]`, recorded as `:failed`, or
`[:wait reason]`, where it made no call because the intent is not ready
yet: a wait is not recorded on the breaker, counts no attempt, and gives
up only by age. Reconciliations run only while the breaker is closed.
The poller's and the relays' constants go: each adapter's
`outbound-runner` carries a `delivery-policy`, a `poll-ms` and, for
Form3 and Modulr, a `reconcile-after-ms`, all required, and optionally a
`concurrency`.

A pass reads at most `pass-limit` intents of each status, 1,000 by
default, the oldest first, scanning the status index under the status
rather than querying it. An intent beyond the limit is later than every
one read, so it can hold none of them. Where the sent read stops at its
limit, a close or reissue newer than the last sent one read stays the
pass without running, holding its account, so it never runs ahead of an
unread sent call it has to wait for; every other pending intent runs.

A `concurrency` above one gives the poller that many worker threads.
While the breaker is closed, a pass runs in rounds: each takes the
intents `intent-queue/runnable` finds may run at once — the oldest due
intent for each subject, with no earlier one for a subject it shares
unsent, nor, for a call that settles first, unsettled — runs them on the
workers, and the next round is worked out from what that one left. A
call it sent frees its subjects for the subject's next call in the same
pass; one it settled or failed leaves the pass; one still pending holds
its subjects, without running again, until the next pass. The pass
then reconciles its due sent intents on the workers too. A probe, and
a poller with no `concurrency`, drain and reconcile in order on the
poller's own thread. A failure that
opens the breaker stops the calls not yet started, while up to
`concurrency` less one already in flight finish. An answer through a
breaker the pass found closed with no failure counted is not recorded,
since it would change nothing. The poller starts its next pass at once
after one that made a call, and after `poll-ms` otherwise. Each pass is
a `<adapter>-pass` span carrying `intents.pending`, `intents.sent` and
`intents.ran`.

An outbox entry about a payment or a transfer, whether the poller or a
payment adapter's webhook wrote it, carries `ordering_key`, which
`intent-poller/ordering-key` reads from the event's data: the payment's
`end-to-end-id`, else the transfer's `transfer-id`. The relay publishes
under it, so a payment's events reach `payment` in the order they were
written however many partitions `topic-schemes-payments-event` has. An
account or verification event carries none and is published unkeyed.

An intent past `max-age-ms` is given up on as the poller gives one up on
its last attempt today: its operation's `:failed` reports it
`:undelivered`, whether or not the breaker is open.

### Synchronous calls

A Companies House lookup and a Confirmation of Payee check are made
while a request waits for them, so neither is an intent and neither is
retried later. Each takes the breaker alone. In
[companies_house.clj](/bases/uk-companies-house-adapter/src/com/repldriven/queenswood/uk_companies_house_adapter/companies_house.clj),
the lookup asks `allow` for `adapter:uk-companies-house`; in each
payment adapter's CoP handler, the check asks it for the adapter's own
destination, which its payments share, since one provider answers both.
`circuit-breaker/guard` makes the call: while the breaker is open it
answers with a `:circuit-breaker/open` anomaly at once, which each
caller already answers as unavailable, rather than holding the request
for the call's timeout; otherwise it calls and records the outcome. A
payment adapter's server includes the same delivery policy its runner
does, so the two agree on their shared breaker, and the Companies House
adapter's config carries a `breaker` of its own.

### The registrars

The `registrar` component keeps an external adapter's subscriptions to
its provider's notifications. An adapter registers three functions with
`registrar/defsubscriptions`, in its base's `subscriptions.clj`:
`:wanted`, the subscriptions it needs; `:held`, asking the provider
which it holds; and `:subscribe`, making one. The last two answer as an
operation's call does, `[:answered …]`, `[:refused reason]` or
`[:retry reason]`. The adapter's `registrar` kind starts
`registrar/start` with its keyword, which runs
`circuit-breaker/start-probe` on `adapter:<adapter>`: each call makes
the subscriptions the provider lacks and marks the adapter ready once
all are held, and only a provider that did not answer counts against
the breaker. It asks every `retry-ms`, five seconds, until everything
is held or while the breaker is not closed, and otherwise every
`check-ms`: fifteen minutes, or thirty seconds under `dev` and `test`,
whose simulators forget their subscriptions on restart. A provider
keeps a subscription through its own outage, so the check is for one
dropped or deleted; that matters because inbound payments and
verification results arrive only as notifications, while outbound
payments are also reconciled after `reconcile-after-ms`. It never gives
up, so a provider down at start-up is subscribed to when it returns.
Since it asks whether or not anything else is sent, it is the adapter's
probe: while the breaker is open it asks every `retry-ms`, and once the
cool-down ends that check is the call that closes or reopens it. The ClearBank,
Form3, Modulr and Onfido adapters register subscriptions; Zyphe is given
its callback with each call and Companies House sends nothing, so
neither has a registrar. `registrar/config-schema` checks each
registrar's `delivery-policy`, `retry-ms` and `check-ms`.

### The email runner

In
[outbound.clj](/components/email/src/com/repldriven/queenswood/email/outbound.clj),
a pass asks `allow` for `smtp` before it claims: open, it claims
nothing; half-open, one delivery as the probe; closed, its batch. A
send's error records `:failed`, a sent message `:answered`; reading the
invitation and recording its token are not the mail server's, and
record nothing. The schedule in `domain.clj` goes: `email.yml` carries
the runner's `poll-ms`, `batch-size` and `claim-lease-ms`, and includes
`email-delivery-policy.yml`, whose `default` is the schedule the runner
had.

### The webhook runner

In
[outbound.clj](/components/webhook/src/com/repldriven/queenswood/webhook/outbound.clj),
the claim asks each endpoint's breaker, in the claim's transaction, how
many of its deliveries it may take: none while open, one as the
half-open probe, and `max-in-flight-per-endpoint` while closed. A call
answered with a 2xx records `:answered`, anything else `:failed`; an
address refused at send time or a signature not produced made no call
and records nothing. The pause rule is gone, and with it the pause
transition, the endpoint's changelog write and its Avro schema; the
`paused` status stays in the enum for an operator's pause, which has no
route yet. `last_success_at`, which only the pause rule read, is
deprecated and leaves the API. `webhook.yml` carries the runner's
`poll-ms`, `batch-size`, `claim-lease-ms`, `request-timeout-ms` and
`max-in-flight-per-endpoint`, and includes
`webhook-delivery-policy.yml`, whose `default` is the day-long schedule
the runner had.

### Tests

- **circuit-breaker** — the state machine over a sequence of outcomes:
  opening at the threshold, no call while open, one probe past
  `retry-at`, closing on its success, reopening with a doubled cool-down
  on its failure. Two claims of one probe, one winning. `retry-policy`
  taking an operation's entry over the default. `guard` calling through
  a closed breaker and answering at once, without calling, through an
  open one. `start-probe` opening a destination that does not answer and
  closing it once it does.
- **registrar** — each subscription a provider lacks is made, the
  adapter marked ready once all are held, and one the provider forgot is
  made again; a provider down at start-up opens the adapter's breaker,
  and once it answers is subscribed to and closes it; once everything is
  held it is not asked again before `check-ms`, unless the breaker opens.
- **intent-poller** — an adapter whose calls fail opens its breaker,
  and the intents behind the opening keep their attempts; an open
  breaker calls nothing; a probe's answer lets the rest through; an
  intent past `max-age-ms` fails while the breaker is open; with a
  `concurrency`, a pass runs intents for different subjects at once and a
  later one for a subject in a later round, an intent left pending holds
  a later one for its subject, and a pass reads only the oldest up to
  `pass-limit`.
- **intent-queue** — `runnable` holding an intent behind an unsent one
  for a subject it shares, a call that settles first behind a sent one,
  and one not yet due.
- **email** — a pass claims nothing while the mail server's breaker is
  open, the delivery left due with no attempt counted, and claims it
  once the breaker closes; a failed attempt backs off by the policy and
  fails the delivery past its maximum attempts or age.
- **webhook** — a failing endpoint opens its breaker; an open breaker
  claims none of its deliveries, and the endpoint stays enabled; past
  the cool-down the probe delivers and closes it. The backoff and
  give-up by attempts and by age.
- **test-scenarios** — `providers/provider-outage-holds-payments`
  starts an outage on the Modulr simulator, which answers 503 to every
  call while it lasts, waits for `adapter:modulr` to open, ends the
  outage, and finds the payment completed and the breaker closed.
- **test-api-scenarios** — `journeys/webhooks/3-endpoint-outage-and-recovery`:
  a customer's endpoint down and back up gets every notification raised
  during the outage, with nothing asked of the customer and the endpoint
  enabled throughout, as the webhooks PRD's journey has it.

## Alternatives Considered

- **A JVM circuit-breaker library.** Rejected: its state is in memory,
  each replica's own, and the webhook and email runners share their
  work across replicas through FDB.
- **A breaker per item.** Rejected: an item's retry schedule is that
  already, and it is the destination that is down.
- **The webhook pause kept as an escalation.** Rejected: a breaker open
  past a window would need the customer to lift it, which is manual
  work for every customer whose endpoint had an outage.
- **Waiting while open counted as an attempt.** Rejected: an outage
  would spend every queued item's attempts as it does today.
- **Give up only on attempts.** Rejected: with waiting not counted, an
  item would wait out an outage of any length, a payment for days.

## Known Limitations

- **A shared breaker shares a partial outage.** One adapter's breaker
  covers all its operations, so a provider failing only its account
  calls stops its payments too.
- **An item can open its destination's breaker.** An item that fails
  for its own reason with a 5xx counts against the destination, and
  enough of them in a row open it for every item, until the probe
  succeeds or the items reach their maximum age.
- **A failing destination costs a write per call.** Each failed call
  increments the record until the breaker opens.
- **Not every destination has a probe of its own.** The four adapters
  with a registrar are probed by it. Zyphe, Companies House, the mail
  server and a customer's endpoint are probed by the next item, so one
  with nothing to send stays open until something is.
- **An endpoint paused before the breaker stays paused.** The platform
  no longer pauses one, but an endpoint it paused earlier is enabled by
  its customer, as it was.
- **An opening breaker lets the calls in flight finish.** With a
  `concurrency`, up to that many less one calls already started when a
  failure opens the breaker still go to the destination.
- **A rotated Modulr webhook secret is not resubscribed.** The registrar
  compares a subscription's type and URL, all Modulr returns, so a new
  `modulr-webhook` secret reaches Modulr only once its subscriptions are
  deleted, and until then every delivery fails its signature.
- **A probe claimed and not made holds its lease.** A runner that dies
  holding a probe leaves the breaker half-open until `probe-lease-ms`
  passes.
- **The two runners order their writes differently.** The webhook
  runner asks the endpoint's breaker inside its claim transaction
  and saves the outcome before recording the breaker; the email
  runner asks in a transaction of its own and records the breaker
  first.

## References

- [prd/webhooks.md](../prd/webhooks.md) — what a customer is told of an
  endpoint's delivery, and the outage journey the breaker serves.
- [prd/payments.md](../prd/payments.md) — the payments a provider
  outage would otherwise fail.
- [tdd/webhooks.md](webhooks.md) — the webhook runner and its claim.
- [tdd/outbound-email.md](outbound-email.md) — the email runner and its
  schedule.
- [tdd/transaction-processing.md](transaction-processing.md) — the
  intent and the poller that drains it.
- [tdd/bank-providers.md](bank-providers.md) — the adapters an
  installation runs, each a destination.
- [ADR-0034](../adr/0034-outbound-calls-go-through-a-breaker-on-their-destination.md)
  — the decision this builds.
- [ADR-0033](../adr/0033-operations-reach-a-provider-in-the-order-they-were-accepted.md)
  — the order a half-open probe keeps.
- [schema-evolution](../recipes/code/schema-evolution.md) — the new
  store and the meta-data version.
- [system-configurations](../recipes/code/system-configurations.md) —
  the `delivery-policy` entries and their `!profile` and `!env` values.
- [CircuitBreaker](https://martinfowler.com/bliki/CircuitBreaker.html)
  — the pattern's states.
