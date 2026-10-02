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
transaction, so the records reflect one commit order, and a brick that
reacts to another's event re-reads the current record and guards its
status, as ADR-0021 has it, so a late or repeated event is skipped
rather than applied wrongly. The order is lost where a request leaves
for a provider, which keeps its own copy of what the platform holds.

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

Every one of these changes already writes a changelog entry in the
commit that makes it, and the entries are totally ordered: a changelog
entry's versionstamp is its commit version, comparable across every
store in the database. What is missing is a reader that keeps that
order across stores. Each route also has a domain brick deciding what
the provider must do, so the payment brick knows which providers hold
each account's money and the cash-account brick that a closure needs a
call, where ADR-0021 has bricks react to changelogs rather than
orchestrate, and ADR-0030 keeps providers out of domain components.

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
  what each must be asked, and the outbox is a new store beside the
  changelogs that already record the same commits.
- **Merge the changelogs a kind of provider acts on into one stream in
  commit order, and let a reactor for that kind decide what to ask.** The
  domain records what happened, as it already does; the order is the
  commits'; and what a provider needs is known only on the provider's
  side.

## Decision

The changelog entries a kind of provider acts on are relayed as one
stream in versionstamp order, and a reactor for that kind consumes the
stream in order, decides what the bank's provider must be asked, and
sends it on that provider's command channel, which the adapter takes in
order for each subject.

The decision has these parts:

- Record every change a provider must act on as an entry in its store's
  changelog, in the commit that makes it: an account's status change, a
  posting, an outbound payment's submission, an inbound's return and a
  verification session's opening.
- Carry on each entry what a reactor decides from, as it was at that
  commit, the provider account an account is held in among it, so a
  reactor reads nothing whose later value could disagree with the
  stream.
- Relay each kind of provider's changelogs as one stream: read them at
  one snapshot read version, merge their entries in versionstamp order,
  publish them verbatim, and advance one cursor, the last versionstamp
  published, across them all.
- Key the stream by bank, so a bank's entries keep their order however
  the topic is partitioned.
- Give each kind of provider one reactor, which handles its stream one
  entry at a time in order, branches on the bank's provider's
  declaration, never its name, and sends what that provider needs:
  opens, closes and reissues, the transfers that mirror a posting where
  the provider holds each account's money, submits and returns for
  payments, and submits for verifications.
- Make a reactor's sends idempotent, with each command's dedup key taken
  from the entry it answers, so a redelivered entry asks nothing twice.
- Give each provider one command channel, keyed by the subject a command
  acts on, so a payment provider's separate payment and account
  channels become one.
- Send a provider's intents in order for each subject: a pending or
  retrying intent holds back later intents for its subject, and for no
  other. End a command the provider refuses for good, or that exhausts
  its attempts, as a failure the domain hears.
- Never send a provider command from a domain brick.
- Keep the rest of ADR-0030: configuration names each provider's
  channel, the event channels stay shared, and a domain component never
  names a provider.

## Consequences

Easier:

- A payment provider closes an account only after the transfers that
  emptied it, pays from an account only after the transfer that funded
  it, and never moves money for an address a reissue has replaced.
- The domain bricks stop knowing what providers need: the mirror
  transfers and the provider calls leave the payment and cash-account
  bricks for the reactor of their kind.
- No new store: the changelogs already written are the record, and the
  stream is a read across them.
- A second command to an identity verification provider, or a new kind
  of provider, is ordered from its first command, as a reactor and a
  stream of the changelogs it acts on.
- A retry handles a provider's transient failure, not the platform's
  ordering, so the close relay's retry of a refusal can go.

Harder:

- A reactor handles one bank's entries one at a time, so a bank's later
  operations wait behind its earlier ones, across all its accounts, not
  only the account an operation concerns.
- An intent at the head of a subject's queue holds that subject's later
  commands until it settles or gives up, so a provider that is slow for
  one account is slow for everything asked of that account.
- Entries carry values a reactor would otherwise look up, the provider
  account among them, so their schemas grow with what reactors decide
  from, and an entry written before a field was added lacks it.
- A merged relay reads several changelogs each pass and moves one
  cursor across them, so its passes are larger than one store's, and a
  changelog it should read but does not leaves that store's changes out
  of the order unnoticed.
- The provider calls move out of the domain bricks into reactors, and a
  payment provider moves from two command channels to one, a migration
  of every adapter's consumers.
- A domain brick that sends a provider command itself brings the race
  back unnoticed. A check in `enforce-idioms.sh` refusing a provider
  command sent outside a reactor is what makes the drift visible.

Amends [ADR-0030](0030-a-bank-chooses-its-providers-when-it-is-created.md),
whose payment providers' two command channels each become one, and whose
domain components no longer send provider commands at all. Related:
[ADR-0021](0021-changelog-relay.md), whose relay this extends to read
several changelogs as one, and [ADR-0019](0019-processor-packaging.md),
on which service runs it.
