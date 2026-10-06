# 43. A per-account provider's transfers go in batches

<!-- tessl-plugin: design -->

## Status

**Proposed**

## Context

The property wanted is that a bank whose provider holds each account's
balance keeps the provider's balances in step with its ledger at a
day's volume, a million movements, within the day, and keeps every
account's calls in an order the provider cannot refuse.

Under `balances: per-account`, every posting that changes a cash
account's posted balance is mirrored as a transfer between provider
accounts, as [payments](../tdd/payments.md) designs it, and each
transfer is one call: a `POST /payments` to Modulr naming one source
and one destination.
[ADR-0033](0033-operations-reach-a-provider-in-the-order-they-were-accepted.md)
sends a subject's intents in order, and a transfer's subjects are its
debtor and creditor accounts. A posting between a cash account and a
GL account is mirrored against the bank's own-funds account, so every
capitalisation, reward, fee and funding transfer names it, and the
intent poller sends them one a round. On kind against the simulator a
round takes a few milliseconds, and a 30,000-call backlog drained in
under four minutes. Against the provider a round waits on the
provider's answer: at 200 ms a call, a capitalisation over 10,000
accounts takes over half an hour, and over a million accounts more than
two days, however many workers the poller has.

Modulr takes up to 10,000 payments in one request to
`/batchpayments`. Each payment names its own source, destination,
amount and external reference; `submissionType: BULK` treats them as
separate payments, and `strictProcessing: false` lets one be refused
without the rest. The request is accepted with 202, and the payments
are made after it.

The shortlist:

- **More workers.** Rejected: workers run different subjects at once,
  and the transfers that name the own-funds account share one.
- **One pooled account per bank.** Rejected, as in
  [payments](../tdd/payments.md): a provider holding a balance per
  account issues an address per account and routes nothing to a shared
  one.
- **Net each account's movements over a window.** Rejected: the
  own-funds account still pays each account once a window, and the
  provider's balances lag the ledger by the window, so an outbound the
  scheme takes from a provider account may find it short.
- **Upload a payment file.** Rejected: Modulr's file is a Bacs 18
  upload a person makes in its portal.
- **Send a round's transfers as one batch request, and let a round
  take every debit from one account together,** since a batch of
  debits from one account needs only their total in its balance.

## Decision

A per-account provider's transfers and payments go in batch requests:
each round of the intent poller sends the intents of a batched
operation as one request, and a round takes several intents for one
subject where none of them can be refused for want of another's money,
so a capitalisation's transfers out of the own-funds account go a
thousand a request rather than one a round.

The parts:

- Let an operation registered with `defoperations` declare a batch call
  taking a round's intents of that operation and returning a result for
  each, with `batch-limit` in the poller's system configuration, at
  most the provider's own limit.
- Name each of an intent's subjects with its role: `debit` where money
  leaves the subject's provider account, `credit` where it arrives, and
  `exclusive` for an open, a close or a reissue.
- Hold a later intent behind an earlier one sharing a subject where
  either is `exclusive`, or the earlier is a credit and the later a
  debit, and in no other case, so debits from one account run together
  and credits to it run with anything before them.
- Hold a debit behind an earlier credit to its subject until the credit
  settles, not only until it is sent, since a batch's payments are made
  after the request is accepted.
- Send a batch as `BULK` with `strictProcessing: false`, each payment's
  external reference its intent's dedup key, so a refused payment fails
  its own intent alone.
- Keep an intent in the request it was first sent in until the request
  is answered: record the request on each intent, and resend the same
  request with its nonce on a retry, so a lost answer creates no
  payment twice.
- Settle each payment from its own webhook or reconciliation, matched
  by its external reference, as a single payment is now.
- Count a failed request once against the destination's breaker, as
  [ADR-0034](0034-outbound-calls-go-through-a-breaker-on-their-destination.md)
  counts a call, leaving every intent in it due.

The rest of ADR-0033 stands: a subject's intents that may be refused for
another's money still reach the provider in the order they were
accepted.

## Consequences

Easier:

- A capitalisation over N accounts is N divided by the round's size in
  requests, a thousand accounts a request, rather than N calls one
  after another.
- Payments between different customers go a thousand a request.
- The poller and the queue stay provider-neutral: an operation opts in,
  and an adapter for a pooled provider, which mirrors nothing, is
  unchanged.

Harder:

- What `/batchpayments` does beyond its reference is unconfirmed: how
  each payment's id and outcome is reported, whether a batch sent
  through the API waits for someone to approve it, how a retried
  request is recognised, and Modulr's rate limits. The sandbox request
  lists them, and this decision is Proposed until they are answered.
- An adapter that names a subject's role wrongly can send a debit ahead
  of the credit it needs, which the provider refuses. The domain
  scenarios' `assert-provider-balances` step compares each provider
  account's balance with the ledger, and the load tests' books check the
  provider's total, which is the audit that makes it visible.
- A refused request holds every intent in it until it is retried.
- The provider still reports each payment by its own webhook, a million
  a day inbound at that volume.
