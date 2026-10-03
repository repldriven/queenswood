# Outbound delivery

> **Status: proposal.** The intent poller, the webhook runner and the
> email runner exist, each retrying its own items on constants in code.
> The Proposed Solution is the build list, and
> [First slice](#first-slice) says what comes first.

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
  registered its operations with `defoperations`. A retryable failure
  waits from one second, doubling to a minute, and an intent gives up
  after twenty attempts, ten for the IDV adapters; sent payments are
  reconciled five minutes on. The adapters' `outbound-runner` entries in
  `components/resources/resources/system/` set some of these, and the
  rest are constants in the poller and the relays.
- **The webhook runner.**
  [outbound.clj](/components/webhook/src/com/repldriven/queenswood/webhook/outbound.clj)
  claims due deliveries under a 60-second lease, at most two in flight
  per endpoint, and calls each with a 10-second timeout. A failure waits
  from 30 seconds, growing fourfold to four hours, for about a day, then
  the delivery is failed and kept. The pause rule pauses an endpoint
  with no success for a day across five attempts, and only the customer
  resumes it. The constants are in the webhook `domain.clj`; the runner
  takes `retry-schedule-ms` and `pause-rule` from configuration, which
  only the scenario rig sets.
- **The email runner.**
  [outbound.clj](/components/email/src/com/repldriven/queenswood/email/outbound.clj)
  claims due deliveries under a 60-second lease and sends each through
  the mail server, on the webhook runner's schedule, its constants
  repeated in the email `domain.clj`.
- **No loop knows its destination is down.** Each counts every failed
  call against the item it was for, so an outage longer than an item's
  schedule fails it.

## Proposed Solution

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
Request timeouts stay where each loop's call sets them.

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
Form3 and Modulr, a `reconcile-after-ms`, all required.

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
`:open` answers the request as unavailable at once, rather than holding
it for the call's timeout; `:probe` and `:closed` make the call and
record its outcome. Each adapter's server config carries the
`breaker` policy alone.

### The email runner

In
[outbound.clj](/components/email/src/com/repldriven/queenswood/email/outbound.clj),
a pass asks `allow` for `smtp` before it claims. A send's error records
`:failed`, a sent message `:delivered`; reading the invitation and
recording its token are not the mail server's, and record nothing. The
schedule in `domain.clj` goes, and `email.yml` carries the
`delivery-policy`.

### The webhook runner

In
[outbound.clj](/components/webhook/src/com/repldriven/queenswood/webhook/outbound.clj),
the claim skips endpoints whose breaker is open, and claims one delivery
for an endpoint whose breaker is half-open. A 2xx records `:delivered`,
anything else `:failed`. The pause rule goes: `should-pause?`,
`pause-window-ms`, `pause-minimum-attempts`, `last-success-at` and the
runner's `pause-rule`. The `paused` status stays, for a person's pause,
and an endpoint the platform paused before this lands is enabled again
by the slice's migration. `webhook.yml` carries the `delivery-policy`,
its `default` the day-long schedule the runner has today, and the
webhooks TDD, PRD and API descriptions drop the platform's pause.

### First slice

The `circuit-breaker` component and its store, with the policy schema,
and the intent poller taking both, since an adapter's outage failing
payments is the loss this stops. The email runner follows, then the
webhook runner with the pause rule's removal.

### Tests

- **circuit-breaker** — the state machine over a sequence of outcomes:
  opening at the threshold, no call while open, one probe past
  `retry-at`, closing on its success, reopening with a doubled cool-down
  on its failure. Two claims of one probe, one winning. `policy` taking
  an operation's entry over the default.
- **intent-poller** — an adapter whose calls fail opens its breaker,
  and the intents behind the opening keep their attempts; an open
  breaker calls nothing; a probe's answer lets the rest through; an
  intent past `max-age-ms` fails while the breaker is open.
- **test-scenarios** — `providers/provider-outage-holds-payments`
  starts an outage on the Modulr simulator, which answers 503 to every
  call while it lasts, waits for `adapter:modulr` to open, ends the
  outage, and finds the payment completed and the breaker closed.
- **test-api-scenarios** — a customer's endpoint down and back up gets
  every notification, its endpoint never paused, which
  `journeys/webhooks/3-endpoint-outage-and-recovery` asserts in place of
  the pause, with the webhooks PRD's journey it follows.

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
- **No probe of its own.** A half-open breaker probes with the next
  item, so a destination with nothing to send stays open until
  something is.

## References

- [prd/webhooks.md](../prd/webhooks.md) — what a customer is told of an
  endpoint's delivery, which drops the platform's pause.
- [prd/payments.md](../prd/payments.md) — the payments a provider
  outage would otherwise fail.
- [tdd/webhooks.md](webhooks.md) — the webhook runner, its schedule and
  the pause rule this removes.
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
