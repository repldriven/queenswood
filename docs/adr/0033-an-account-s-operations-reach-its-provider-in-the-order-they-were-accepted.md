# 33. An account's operations reach its provider in the order they were accepted

## Status

**Proposed**

## Context

A payment provider acts on a bank's accounts at the platform's request:
it opens and closes them, reissues their addresses, sends their payments
and returns, and, where it holds each account's money separately, moves
money between them to match the ledger. The property wanted is that the
provider sees the requests for one account in the order the platform
committed the changes that caused them, so it never acts on an account
in a state the ledger has already left.

Today the commands reach a provider by four routes:

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
retry waits out its backoff while later calls go ahead. The commits
themselves are totally ordered: a changelog entry's versionstamp is its
commit version, comparable across every store in the database.

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
- **One outbox per provider.** Rejected: it would put the provider into
  the storage, so adding one would mean a store and a relay runner of its
  own, the per-provider plumbing ADR-0030 keeps out of the domain.
- **Write every provider command in the transaction that causes it, into
  one provider-neutral outbox, relayed in commit order and sent in order
  per account.** The commit order is already total. Writing the command
  with its cause keeps it, the bank's provider key on each entry routes
  it as configuration already does, and the relay and the adapter only
  have to not lose the order.

## Decision

Every command to a payment provider is written to one provider-neutral
outbox in the same transaction as the commit that causes it, relayed in
versionstamp order onto the bank's provider's one command channel keyed
by the provider account it acts on, and sent by the adapter in that order
for each provider account.

The decision has these parts:

- Write each provider command to the outbox in the transaction that
  commits its cause: an account's open, close or reissue with its status
  change, the transfers that mirror a posting with the posting, an
  outbound payment's submit with the payment, and an inbound's return
  with its handling. Never send one from a handler reacting to the
  commit afterwards.
- Give each entry the neutral command, the bank's provider key, and the
  provider account it acts on, or the bank's own funds where it acts on
  no account of its own; never a provider's name or a vendor's request.
- Relay the outbox in versionstamp order, publishing each entry onto the
  command channel configuration names for its provider key, keyed by its
  provider account; each provider has one command channel, in place of
  its separate payment and account command channels.
- Send a provider's intents in order for each provider account: a
  pending or retrying intent holds back later intents for its account,
  and for no other.
- End a command the provider refuses for good, or that exhausts its
  attempts, as a failure the domain hears, so that one account's queue
  cannot hold indefinitely.
- Keep the rest of ADR-0030: configuration names each provider's
  channel, the event channels stay shared, and a domain component never
  names a provider.

## Consequences

Easier:

- A provider closes an account only after the transfers that emptied
  it, pays from an account only after the transfer that funded it, and
  never moves money for an address a reissue has replaced.
- A retry handles a provider's transient failure, not the platform's
  ordering, so the close relay's retry of a refusal can go.
- The outbox is one ordered record of everything asked of every
  provider, for each bank and each account.
- Adding a provider stays configuration: its adapter, its declaration
  and its one channel, with no store, relay or domain brick changed.

Harder:

- The transfers that mirror a posting are worked out inside the
  posting's transaction, reading the bank's provider declaration and the
  accounts involved on a path every payment takes.
- An intent at the head of an account's queue holds that account's
  later commands until it settles or gives up, so a provider that is
  slow for one account is slow for everything asked of that account.
- One relay serves every provider, so a provider's channel that is slow
  to take a publish holds up the commands behind it for the others. A
  cursor per provider over the same outbox removes that, should it come
  to matter.
- The outbox and its relay runner are new, in
  `exclusive-dispatchers-service`, and moving from two command channels
  per provider to one is a migration of every adapter's consumers.
- A handler that sends a provider command after a commit, rather than
  writing it with the commit, brings the race back unnoticed. A check in
  `enforce-idioms.sh` refusing a provider command sent outside the
  outbox writer is what makes the drift visible.

Amends [ADR-0030](0030-a-bank-chooses-its-providers-when-it-is-created.md),
whose command channels per provider become one. Related:
[ADR-0021](0021-changelog-relay.md), whose relay this reuses, and
[ADR-0019](0019-processor-packaging.md), on which service runs it.
