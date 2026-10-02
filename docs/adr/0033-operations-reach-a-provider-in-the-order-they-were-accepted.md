# 33. Operations reach a provider in the order they were accepted

## Status

**Proposed**

## Context

A provider acts on what the platform holds at the platform's request. A
payment provider opens and closes a bank's accounts, reissues their
addresses, sends their payments and returns, and, where it holds each
account's money separately, moves money between them to match the
ledger. An identity verification provider runs a check on a party. The
property wanted is that a provider sees the requests for one subject,
an account or a party's verification, in the order the platform
committed the changes that caused them, so it never acts on a subject
in a state the ledger has already left.

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
party's closure or a re-verification, would make two. The commits
themselves are totally ordered: a changelog entry's versionstamp is its
commit version, comparable across every store in the database.

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
- **One outbox per provider, or per kind of provider.** Rejected: it
  would put the provider into the storage, so adding one would mean a
  store and a relay runner of its own, the per-provider plumbing
  ADR-0030 keeps out of the domain.
- **Write every provider command in the transaction that causes it, into
  one provider-neutral outbox, relayed in commit order and sent in order
  per subject.** The commit order is already total. Writing the command
  with its cause keeps it, the bank's provider key on each entry routes
  it as configuration already does, and the relay and the adapter only
  have to not lose the order.

## Decision

Every command to a provider, of any kind, is written to one
provider-neutral outbox in the same transaction as the commit that
causes it, relayed in versionstamp order onto the command channel of the
bank's provider of that kind, keyed by the subject it acts on, and sent
by the adapter in that order for each subject.

The decision has these parts:

- Write each provider command to the outbox in the transaction that
  commits its cause: an account's open, close or reissue with its status
  change, the transfers that mirror a posting with the posting, an
  outbound payment's submit with the payment, an inbound's return with
  its handling, and a verification session's submit with the session.
  Never send one from a handler reacting to the commit afterwards.
- Give each entry the neutral command, the kind of provider, the bank's
  provider key for that kind, and its subject: the provider account a
  payment command acts on, or the bank's own funds where it acts on no
  account of its own, and the party a verification command acts on.
  Never a provider's name or a vendor's request.
- Relay the outbox in versionstamp order, publishing each entry onto the
  command channel configuration names for its provider, keyed by its
  subject. Each provider has one command channel, so a payment
  provider's separate payment and account channels become one.
- Send a provider's intents in order for each subject: a pending or
  retrying intent holds back later intents for its subject, and for no
  other.
- End a command the provider refuses for good, or that exhausts its
  attempts, as a failure the domain hears, so that one subject's queue
  cannot hold indefinitely.
- Offer a new kind of provider the same way: its commands written to
  the outbox with their causes, its subject named on each entry, and its
  one channel in configuration.
- Keep the rest of ADR-0030: configuration names each provider's
  channel, the event channels stay shared, and a domain component never
  names a provider.

## Consequences

Easier:

- A payment provider closes an account only after the transfers that
  emptied it, pays from an account only after the transfer that funded
  it, and never moves money for an address a reissue has replaced.
- A second command to an identity verification provider, or a new kind
  of provider, is ordered from its first command, rather than when the
  race it allows is found.
- A retry handles a provider's transient failure, not the platform's
  ordering, so the close relay's retry of a refusal can go.
- The outbox is one ordered record of everything asked of every
  provider, for each bank and each subject.
- Adding a provider stays configuration: its adapter, its declaration
  and its one channel, with no store, relay or domain brick changed.

Harder:

- The transfers that mirror a posting are worked out inside the
  posting's transaction, reading the bank's provider declaration and the
  accounts involved on a path every payment takes.
- An intent at the head of a subject's queue holds that subject's later
  commands until it settles or gives up, so a provider that is slow for
  one account is slow for everything asked of that account.
- One relay serves every provider, so a provider's channel that is slow
  to take a publish holds up the commands behind it for the others. A
  cursor per provider over the same outbox removes that, should it come
  to matter.
- The outbox and its relay runner are new, in
  `exclusive-dispatchers-service`, and moving a payment provider from
  two command channels to one is a migration of its adapter's consumers.
- A handler that sends a provider command after a commit, rather than
  writing it with the commit, brings the race back unnoticed. A check in
  `enforce-idioms.sh` refusing a provider command sent outside the
  outbox writer is what makes the drift visible.

Amends [ADR-0030](0030-a-bank-chooses-its-providers-when-it-is-created.md),
whose payment providers' two command channels each become one. Related:
[ADR-0021](0021-changelog-relay.md), whose relay this reuses, and
[ADR-0019](0019-processor-packaging.md), on which service runs it.
