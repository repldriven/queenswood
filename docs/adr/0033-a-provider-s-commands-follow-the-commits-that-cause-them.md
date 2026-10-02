# 33. A provider's commands follow the commits that cause them

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

Today the commands reach a provider by four routes. A status change on
an account commits to the cash-accounts changelog, its relay publishes
it, and the cash-account brick's handler sends the open, close or
reissue on the provider's account command channel. A posting commits to
the transactions changelog, its relay publishes it, and the payment
brick's handler sends the transfers that mirror it on the payment
command channel. An outbound payment's processor sends its submit
straight after its commit, with no relay, and an inbound's handler
sends its return. Each route has its own relay or none, its own topic
and its own consumer, so the order the commands reach the adapter in is
the order the routes happen to deliver, not the order of the commits.
Under load the provider is asked to close an account before the
transfer that emptied it arrives, to pay from an account before the
transfer that funded it, and to move money for an account whose address
a reissue has already replaced. The adapter's relay then reorders
further, since a retry waits out its backoff while later calls go
ahead. The commits themselves are totally ordered: a changelog entry's
versionstamp is its commit version, comparable across every store in
the database.

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
- **Write every provider command in the transaction that causes it, into
  one outbox per provider, relayed in commit order and sent in order per
  account.** The commit order is already total. Writing the command with
  its cause keeps it, and the relay and the adapter only have to not
  lose it.

## Decision

Every command to a payment provider is written to that provider's outbox
in the same transaction as the commit that causes it, relayed in
versionstamp order onto one command channel per provider keyed by the
provider account it acts on, and sent by the adapter in that order for
each provider account.

The decision has these parts:

- Write each provider command to the bank's provider's outbox in the
  transaction that commits its cause: an account's open, close or
  reissue with its status change, the transfers that mirror a posting
  with the posting, an outbound payment's submit with the payment, and
  an inbound's return with its handling. Never send one from a handler
  reacting to the commit afterwards.
- Key each outbox entry by the provider account it acts on, or by the
  bank's own funds where it acts on no account of its own.
- Relay each provider's outbox in versionstamp order onto one command
  channel for that provider, published with the entry's key, in place
  of its separate payment and account command channels.
- Send a provider's intents in order for each provider account: a
  pending or retrying intent holds back later intents for its account,
  and for no other.
- End a command the provider refuses for good, or that exhausts its
  attempts, as a failure the domain hears, so that one account's queue
  cannot hold indefinitely.
- Keep the rest of ADR-0030: the bank's provider selects the outbox and
  configuration names its channel, the event channels stay shared, and
  a domain component never names a provider.

## Consequences

Easier:

- A provider closes an account only after the transfers that emptied
  it, pays from an account only after the transfer that funded it, and
  never moves money for an address a reissue has replaced.
- A retry handles a provider's transient failure, not the platform's
  ordering, so the close relay's retry of a refusal can go.
- The outbox is one ordered record of everything asked of a provider,
  for each bank and each account.

Harder:

- The transfers that mirror a posting are worked out inside the
  posting's transaction, reading the bank's provider declaration and the
  accounts involved on a path every payment takes.
- An intent at the head of an account's queue holds that account's
  later commands until it settles or gives up, so a provider that is
  slow for one account is slow for everything asked of that account.
- Each provider gains an outbox store and a relay runner in
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
