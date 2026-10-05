# 41. Each consumer names its performers and the key that chooses them

<!-- tessl-plugin: design -->

## Status

**Proposed**

## Context

mono's
[ADR-0040](https://github.com/repldriven/mono/blob/main/docs/adr/0040-a-consumer-hands-each-message-to-a-performer-chosen-by-its-key.md)
hands each message a subscription receives to one of its performers,
chosen by the message's key or a narrower key the subscriber names.
Each workspace decides how many performers a consumer has and which
key chooses them.

We want a consumer that falls behind to get concurrency from its
configuration, with no topic recreated and no replica added. We also
want every consumer to keep its messages in the order it needs them.

Today a consumer handles one message at a time. Concurrency costs a
partition and a replica each. Onboarding on kind reached 36.6
customers a second of the 40 sent, with the party and IDV command
topics on four partitions and `operational-processors-service` on four
replicas. The next queue was the webhook runner's consumer of
`topic-idvs-event`: one partition, one `external-adapters-service`
replica. `topic-bank-activity-event` is keyed by bank, so it handles
one bank's entries one at a time, however many partitions it has.

The shortlist:

- **Leave every consumer at one performer.** Rejected: concurrency
  still costs a partition and a replica each, and one bank's activity
  stays serial.
- **One performer count for the installation.** Rejected: a handler
  that takes a few milliseconds and one that waits on a provider need
  different counts, and a high count on a cheap handler only adds
  contention.
- **Narrow every key as far as the payload allows.** Rejected: a
  posting's activity entry names several accounts, and one key cannot
  order it against all of them.
- **Each consumer's count and key, set where it is measured.** A
  performance run sets the count. What the consumer must keep in order
  sets the key.

## Decision

Each consumer sets `performers` in its service's `application.yml`,
from what a performance run shows. Its topic's send key chooses the
performer, unless the consumer narrows it to a key that implies the
send key. Partitions set how many replicas a consumer can run, and are
chosen once.

The decision has these parts:

- Set `performers` per consumer in the service's `application.yml`.
  Raise it only when a run shows the consumer behind and the store's
  conflict rate flat. Leave it at 1 everywhere else.
- Send every command for an existing entity keyed by that entity, so
  its performers keep the entity's commands in order. Send a command
  that creates an entity with no key; it goes to the next performer.
- Narrow `topic-bank-activity-event`'s performer key to the entry's
  subject only in a consumer whose entries each name one subject. A
  consumer that handles an entry naming several subjects keeps the
  bank.
- Keep command-response consumers at one performer. Each API process
  reads its replies in a group of its own and hands each reply to the
  request waiting on it.
- Grow a topic's partitions only so a consumer can run more replicas,
  by draining, altering and restarting as
  [performance-testing](../tdd/performance-testing.md) describes.

### Worked example

Each service's consumers during the onboarding load test in
[performance-testing](../tdd/performance-testing.md): the topic and its
partitions, the key each message is sent under, and the performer key
this decision gives it. The table records that moment and is not
updated as consumers, topics and sends change.

| Service | Topic (partitions) | Consumer | Sent under | Performer key |
|---|---|---|---|---|
| operational-processors | `topic-parties-command` (4) | party processor | the party; a create, none | the party; a create, next in turn |
| operational-processors | `topic-idvs-command` (4) | IDV processor | the party for a session; otherwise none | the party |
| operational-processors | `topic-idv-event` (4) | IDV evidence | the verification | the verification |
| operational-processors | `topic-parties-event` (1) | IDV, on a pending party | the party | the party |
| operational-processors | `topic-idvs-event` (1) | party, on a verification's outcome | the party | the party |
| operational-processors | `topic-bank-activity-event` (1) | IDV activity | the bank | the session's party: an opening names one |
| operational-processors | `topic-banks-command` (1) | bank processor | none | next in turn |
| operational-processors | `topic-cash-accounts-command` (1) | cash-account processor | the party for an open, otherwise the account | the same |
| operational-processors | `topic-cash-accounts-event` (1) | cash-account, on a closing's second leg | the account | the account |
| operational-processors | `topic-schemes-account-event` (1) | cash-account, on a provider's account | none | the account, once sent under it |
| operational-processors | `topic-memberships-command` (1) | membership processor | none | next in turn |
| financial-processors | `topic-payments-command` (4) | payment processor | the debtor account | the debtor account |
| financial-processors | `topic-schemes-payments-event` (4) | payment, on a scheme's outcome | the payment's end-to-end id, else its transfer | the same |
| financial-processors | `topic-bank-activity-event` (1) | payment activity | the bank | the bank: a posting names several accounts |
| financial-processors | `topic-payee-checks-command` (1) | payee check processor | none | next in turn |
| financial-processors | `topic-transactions-command` (1) | transaction processor | none | next in turn |
| external-adapters | each provider's command topic (1) | that provider's adapter | the bank | the bank: the intent it records orders the calls |
| external-adapters | `topic-companies-command` (1) | company register adapter | none | next in turn |
| external-adapters | `topic-idvs-event` (1) | webhook runner | the party | the party |
| external-adapters | `topic-parties-event` (1) | webhook runner | the party | the party |
| external-adapters | `topic-cash-accounts-event` (1) | webhook runner | the account | the account |
| external-adapters | `topic-payments-event` (1) | webhook runner | the payment | the payment |
| external-adapters | `topic-rewards-event` (1) | webhook runner | the reward | the reward |
| external-adapters | `topic-invitations-event` (1) | email runner | the invitation | the invitation |

The load test's queue was the webhook runner's row for
`topic-idvs-event`. Keyed by party, its performers handle different
customers' verification events at once, with no partition or replica
added.

## Consequences

Easier:

- A consumer that falls behind gets concurrency from one number in its
  service's configuration and a restart.
- IDV's activity consumer handles one bank's verifications concurrently,
  one session at a time per party. Today the bank's one partition
  makes them serial.
- The webhook runner can keep up with onboarding without another
  `external-adapters-service` replica.

Harder:

- Every performer count comes from a run on kind, where k6 and every
  JVM share the CPUs. A deployed instance sets its counts again from
  its own runs.
- Nothing checks a narrowed key. If a consumer starts handling entries
  that name several subjects, or a new entry kind does, the narrowed
  key reorders what the bank kept in order. The consumer's scenarios on
  Kafka with more than one performer are where this shows.
- Commands for existing entities sent with no key, such as the
  cash-account commands, need a key before their performers go above
  1, or they run out of order.
- The worked example goes stale as consumers are added or rekeyed. It
  was read from each service's `application.yml` and each send's
  `:ordering-key`.
