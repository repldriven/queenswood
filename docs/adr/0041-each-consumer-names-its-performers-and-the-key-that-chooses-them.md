# 41. Each consumer names its performers and the key that chooses them

<!-- tessl-plugin: design -->

## Status

**Proposed**

## Context

mono's
[ADR-0040](https://github.com/repldriven/mono/blob/main/docs/adr/0040-a-consumer-hands-each-message-to-a-performer-chosen-by-its-key.md)
gives every subscription a configured number of performers, each
message going to one chosen by a hash of its key, narrowed where a
subscriber names a narrower key. What it leaves to a workspace is how
many performers each consumer has, and which key chooses them.

The property wanted is that a consumer behind at the knee gets
concurrency from its own configuration, without a topic recreated or a
replica added, and that nothing a consumer must keep in order is
reordered. Today a consumer handles one message at a time and the only
way to add concurrency is a partition and a replica for each unit of
it: the onboarding knee on kind reached 36.6 customers a second at 40
asked with the party and IDV command topics on four partitions and
`operational-processors-service` on four replicas, and its next queue
was the webhook runner's consumer of `topic-idvs-event`, one partition
in one `external-adapters-service` replica. A topic keyed by bank,
`topic-bank-activity-event`, runs one bank's entries one at a time
whatever its partition count.

The shortlist:

- **Leave every consumer at one performer.** Rejected: concurrency stays
  a partition and a replica at a time, and one bank's activity stays
  serial.
- **One performer count for the installation.** Rejected: a consumer
  whose handler takes a few milliseconds and one whose handler waits on
  a provider want different counts, and a single count gives the cheap
  ones contention for nothing.
- **Narrow every key as far as a payload allows.** Rejected: an
  activity entry posting a transaction names several accounts, and one
  key cannot keep it in order against all of them.
- **Each consumer's count and key, set where it is measured.** The
  count follows from a performance run, and the key from what the
  consumer must keep in order, written beside the consumer it applies
  to.

## Decision

Each consumer takes `performers` in its service's `application.yml`,
set from what a performance run shows, and its performers are chosen
by the key its topic is sent under unless that key is narrowed to one
every message sharing it was sent under. Partitions stay the number of
replicas a consumer may run, chosen once.

The decision has these parts:

- Set `performers` per consumer in the service's `application.yml`, and
  raise it only on a run showing the consumer behind and its store's
  conflict rate flat; leave it at 1 elsewhere.
- Key every command for an existing entity by that entity when it is
  sent, so its performers keep that entity's commands in order; a
  command that creates an entity goes unkeyed, to the next performer in
  turn.
- Narrow `topic-bank-activity-event`'s performer key to an entry's
  subject only for a consumer whose entries each name one subject; a
  consumer that handles an entry naming several keeps the bank.
- Leave a command-response consumer at one performer: each API process
  reads its replies in a group of its own and hands each to the request
  waiting on it.
- Grow a topic's partitions only to let a consumer run more replicas,
  following the drain, alter and restart in
  [performance-testing](../tdd/performance-testing.md).

### Worked example

The consumers each service ran at the onboarding knee in
[performance-testing](../tdd/performance-testing.md): the topic and its
partitions, what each message is sent under, and the performer key the
decision gives it. A row changes when a consumer, a topic or a
send does, and this table is not kept up to date with it.

| Service | Topic (partitions) | Consumer | Sent under | Performer key |
|---|---|---|---|---|
| operational-processors | `topic-parties-command` (4) | party processor | the party; a create, none | the party; a create, next in turn |
| operational-processors | `topic-idvs-command` (4) | IDV processor | the party for a session; otherwise none | the party |
| operational-processors | `topic-idv-event` (4) | IDV evidence | the verification | the verification |
| operational-processors | `topic-parties-event` (1) | IDV, a party's pending | the party | the party |
| operational-processors | `topic-idvs-event` (1) | party, a verification's outcome | the party | the party |
| operational-processors | `topic-bank-activity-event` (1) | IDV activity | the bank | the session's party: an opening names one |
| operational-processors | `topic-banks-command` (1) | bank processor | none | next in turn |
| operational-processors | `topic-cash-accounts-command` (1) | cash-account processor | none | the account, once sent under it; an open, next in turn |
| operational-processors | `topic-cash-accounts-event` (1) | cash-account, a closing's second leg | the account | the account |
| operational-processors | `topic-schemes-account-event` (1) | cash-account, a provider's account | none | the account, once sent under it |
| operational-processors | `topic-memberships-command` (1) | membership processor | none | next in turn |
| financial-processors | `topic-payments-command` (4) | payment processor | the debtor account | the debtor account |
| financial-processors | `topic-schemes-payments-event` (4) | payment, a scheme's outcome | the payment's end-to-end id, else its transfer | the same |
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

The onboarding knee's queue at `topic-idvs-event` is the webhook
runner's row: one partition in one replica, keyed by party, so its
performers take different customers' verification events at once
without a partition or a replica added.

## Consequences

Easier:

- A consumer behind at the knee gets concurrency from a number in its
  service's configuration, changed by a restart.
- One bank's identity verifications are handled concurrently, a
  session at a time per party, where its activity log's one partition
  kept them serial.
- The webhook runner keeps pace with onboarding in one
  `external-adapters-service` replica.

Harder:

- Every performer count is a judgement made from a kind run, and kind
  shares its CPUs between k6 and every JVM; a deployed instance's
  counts are set again from its own runs.
- A narrowed key is checked by nobody: a consumer whose entries come to
  name several subjects, or a new entry kind that does, reorders what
  the bank's key kept in order. The consumer's scenarios on Kafka, with
  more than one performer, are where it shows.
- Unkeyed commands for existing entities, such as the cash-account
  commands, need their sends keyed before their performers are raised,
  or they reorder.
- The worked example ages with every consumer added or rekeyed; the
  services' `application.yml` files and the sends' `:ordering-key` are
  what it was read from.
