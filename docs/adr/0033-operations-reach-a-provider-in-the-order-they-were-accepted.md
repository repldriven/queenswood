# 33. Operations reach a provider in the order they were accepted

## Status

**Proposed**

## Context

A provider acts on what the platform holds at the platform's request. A
payment provider opens and closes a bank's accounts, reissues their
addresses, sends their payments and returns, and, where it holds each
account's money separately, moves money between them to match the
ledger. An identity verification provider runs a check on a party. The
property wanted is that a provider is asked to act in the order the
platform committed the changes that call for it, so it never acts on an
account or a party in a state the ledger has already left.

Inside the platform that order already holds. Every operation is an FDB
transaction, and a brick that reacts to another's event guards its own
record's status, as ADR-0021 has it, so a late or repeated event is
skipped rather than applied wrongly. The order is lost where a request
leaves for a provider, which keeps its own copy of what the platform
holds.

Today the commands reach a payment provider by four routes:

1. A status change on an account commits to the cash-accounts
   changelog, its relay publishes it, and the cash-account brick's
   handler sends the open, close or reissue on the provider's account
   command channel.
2. A posting commits to the transactions changelog, its relay publishes
   it, and the payment brick's handler sends the transfers that mirror
   it on the payment command channel.
3. An outbound payment's processor sends its submit straight after its
   commit, with no relay.
4. An inbound's handler sends its return.

Each route has its own relay or none, its own topic and its own
consumer, so the order the commands reach the adapter in is the order
the routes happen to deliver, not the order of the commits. Under load
the provider is asked to close an account before the transfer that
emptied it arrives, to pay from an account before the transfer that
funded it, and to move money for an account whose address a reissue has
already replaced. The adapter's relay then reorders further, since a
retry waits out its backoff while later calls go ahead. An identity
verification provider has one route today, a session's submit sent
after its commit, so nothing overtakes it yet; a second command, a
party's closure or a re-verification, would make two.

The unit the order must hold across is the bank, not the account: a
posting moves money between two of a bank's accounts, so a transfer
from one must reach the provider before a later close of the other.
Banks are independent of each other, so the order needs no wider unit,
and the bank is what the work can be partitioned by.

A customer told through webhooks keeps a copy too, and an account's
notifications and its payments' travel on different topics. That is
not this decision: the customer is told rather than asked, each
notification carries when it happened and the whole resource as its
read route returns it, and a delivery's retries reorder whatever order
it is sent in. Its order per resource is the webhooks design's.

The shortlist:

- **Retry what the provider refuses.** Rejected: it covers only the
  orders a provider refuses, a close of an account holding money among
  them. A payment from an unfunded account is parked rather than
  refused and expires, and a transfer naming a replaced account fails,
  so correctness would rest on each provider's behaviour.
- **One command channel per provider, sent as now.** Rejected: the order
  is lost before the commands are sent, by the separate relays that
  produce them. A channel keeps the order its messages arrive in, and
  these arrive unordered.
- **Hold each command until the ones it depends on settle.** Rejected:
  every pair of commands needs its own check, a close waiting on
  transfers, a payment on its funding, and the check races the handler
  that has not yet recorded what it waits on.
- **Write each provider command in the transaction that causes it, into
  one outbox, relayed in commit order.** Rejected: it keeps the order,
  but every domain transaction then orchestrates its providers, deciding
  what each must be asked.
- **Merge the changelogs a kind of provider acts on into one stream in
  versionstamp order, with one relay and a consumer that reads FDB to
  decide.** Rejected: the order lives in FDB and the topic only
  announces it, so the two disagree whenever a consumer reads a record
  later than the entry it handles; one relay reads every store a kind
  of provider acts on, and nothing partitions it; and the design rests
  on versionstamps comparable across stores, which no other database
  offers.
- **Key the stream by account.** Rejected: a transfer between two
  accounts sits in one account's partition, and a close of the other
  overtakes it in its own.
- **Record a bank's activity in a log of its own, written in the commit
  that makes each change, published keyed by bank, and acted on from the
  event alone.** The topic is the order a consumer sees, each event
  carries what a consumer acts on, and banks spread across shards.

## Decision

Every change a provider must act on writes an activity entry for its bank
in the same commit, carrying what a consumer needs as it was at that
commit; each shard of banks' entries is relayed in commit order to one
topic keyed by bank; and an event processor per kind of provider acts on
each entry from the entry alone and sends what the bank's provider needs
on that provider's one command channel, which the adapter takes in order
for each subject.

The decision has these parts:

- Assign each bank to one of a fixed number of shards by its id, and give
  each shard an activity changelog of its own.
- Write an activity entry, in the commit that makes the change, for an
  account's status change, a posting, an outbound payment's submission,
  an inbound's return and a verification session's opening, into the
  activity changelog of the bank's shard.
- Carry on each entry everything an event processor decides from, as it
  was at that commit: the subjects the change touches, and what the
  command needs of each, its address and its provider account where it
  has one.
- Relay each shard's activity changelog with one `changelog-relay`
  runner, publishing its entries verbatim and in commit order to one
  activity topic, keyed by bank.
- Act on a kind of provider's entries in an event processor of the
  brick that owns that kind, the payment brick for payment providers
  and the idv brick for identity verification: a `<brick>/event-processor`
  kind wrapped in mono's `event-processor/event-processor`, its handler
  in the brick's `events.clj`.
- Decide what to send from the entry, the provider's declaration and
  records whose values never change once written, such as a bank's
  providers and its house account, never from a value a later entry may
  change; an event processor MAY record its own progress, such as the
  provider transfers it sent.
- Branch on the bank's provider's declaration, never its name, and send
  opens, closes and reissues, the transfers that mirror a posting where
  the provider holds each account's money, submits and returns for
  payments, and submits for verifications.
- Take each command's dedup key from the entry it answers, so a
  redelivered entry asks nothing twice.
- An event processor MAY act on a bank's entries for different subjects
  concurrently, holding an entry until every earlier entry sharing one
  of its subjects is done, and an entry touching two subjects until
  both are.
- Give each provider one command channel, keyed by bank, so a payment
  provider's separate payment and account channels become one and a
  command touching two subjects shares a partition with each one's.
- Resolve an identifier the provider issued, such as the provider
  account an account is held in, at the adapter when the call is made,
  from what its own earlier calls recorded, so a command for an account
  still opening needs nothing the entry could not carry.
- Send a provider's intents in order for each subject: a pending or
  retrying intent holds back later intents for its subject, a transfer
  for both its accounts, and for no other; where the provider refuses a
  call while money is still moving, as a close, that call also waits for
  the earlier ones to settle. End a command the provider refuses for
  good, or that exhausts its attempts, as a failure the domain hears.
- Send a provider command only from an event processor that acts on
  the activity topic.
- Keep the rest of ADR-0030: configuration names each provider's
  channel, the event channels stay shared, and a domain component never
  names a provider.

## Consequences

Easier:

- A payment provider closes an account only after the transfers that
  emptied it, pays from an account only after the transfer that funded
  it, and never moves money for an address a reissue has replaced.
- The topic is the one order every consumer sees: an event processor
  reads no record whose later value could disagree with it, so it
  scales by partition like any keyed consumer, and replaying the topic
  replays the decisions.
- Banks spread across shards, relays and partitions, and a bank's
  accounts across subjects within its partition and at the adapter, so
  the ceiling is each provider's own rate, which serves every bank on
  it.
- The pieces already exist: a changelog per shard written in the
  commit, the `changelog-relay` runner, mono's event processor, and the
  adapters' intents. Nothing depends on versionstamps comparing across
  stores, so another database carries the design with a transactional
  outbox or change data capture.
- A second command to an identity verification provider, or a new kind
  of provider, is ordered from its first command as entries on the
  activity topic and an event processor in the brick that owns the
  kind.
- A retry handles a provider's transient failure, not the platform's
  ordering, so the close relay's retry of a refusal can go.

Harder:

- A bank's entries for one subject wait behind each other, and an entry
  touching two subjects waits for both, so a bank whose accounts move
  money between each other often runs closer to one at a time.
- mono's Kafka consumer handles one message at a time per partition and
  commits each, so acting on subjects concurrently within a partition
  needs it to commit only below the lowest entry still in hand; until
  then a partition's entries run in order, one at a time.
- The number of shards is fixed: moving a bank to another shard would
  need its old shard drained first, so changing the number is a
  migration, not configuration.
- Entries carry values an event processor would otherwise look up, the
  provider account among them, so their schemas grow with what is
  decided from them, and an entry written before a field was added
  lacks it.
- An intent at the head of a subject's queue holds that subject's later
  commands until it settles or gives up, so a provider that is slow for
  one account is slow for everything asked of that account.
- The provider sends move out of the cash-account brick and out of the
  payment brick's processors into its event processor, and a payment
  provider moves from two command channels to one, a migration of every
  adapter's consumers.
- A change a provider must act on that writes no activity entry is never
  sent, and a provider command sent from anywhere else brings the race
  back, both unnoticed. The `provider-command-outside-activity` semgrep
  rule, refusing a provider command named outside an activity event
  processor, and the PRD journeys run on every provider, are what make
  the drift visible.
- An adapter resolves a provider account from what its own opening or
  reissue recorded, so an account opened before it kept that, or by
  another path, falls back to the provider account the command named.

Amends [ADR-0030](0030-a-bank-chooses-its-providers-when-it-is-created.md),
whose payment providers' two command channels each become one, and whose
provider commands are sent only from the activity topic. Related:
[ADR-0021](0021-changelog-relay.md), whose relay publishes each shard's
activity changelog, and [ADR-0019](0019-processor-packaging.md), on
which service runs each shard's runner.
