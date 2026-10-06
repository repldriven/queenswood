# Outbound payments

> **Status: implemented.**

## Objective

Show how an outbound payment, leaving a Queenswood account through UK
Faster Payments, moves through the platform: the submission that
reserves its amount, the provider call it becomes, and each outcome the
provider reports, with the records each FDB transaction writes.

In scope: `submit-outbound-payment` from the API to its reply, the
bank's activity entry that carries it to the provider's adapter, the
intent and the call, and the settlement, rejection, hold and return the
adapter reports back.

Out of scope: the provider declaration and the adapter contract, see
[payments.md](payments.md); internal and inbound payments, see
[payments-internal.md](payments-internal.md) and
[payments-inbound.md](payments-inbound.md); the intent poller's
breaker, retries and reconciliation timing, see
[outbound-delivery.md](outbound-delivery.md); how a balance is kept, see
[chart-of-accounts.md](chart-of-accounts.md).

## Background

- **The request.** `POST /v1/payments/outbound` in `bases/api`'s
  `payment` routes sends `submit-outbound-payment` on
  `topic-payments-command`, partitioned by the debtor account, and
  answers 201 from the reply with the payment `pending`.
- **The processors.** `payment/processor` submits, in `core.clj`
  `submit-outbound`; `payment/activity-event-processor` sends the
  provider command, in `events/activity.clj`; and
  `payment/event-processor` handles the scheme events, in
  `events/outbound.clj`. All three run in
  `financial-processors-service`.
- **The adapter.** `<provider>-adapter/command-processor`, in
  `external-adapters-service`, consumes the command into an intent in
  its `<provider>-relay` store, and the intent poller,
  `<provider>-relay/outbound-runner`, makes the call, as
  [outbound-delivery.md](outbound-delivery.md) describes. The adapter
  base's webhook handlers write what the provider reports to its
  outbox, which `changelog-relay/runners` in
  `exclusive-dispatchers-service` publishes on
  `topic-schemes-payments-event`, partitioned by the end-to-end id,
  else the transfer.
- **Statuses.** An outbound payment is `pending`, `held`, `completed`,
  `failed` or `returned`.

## Solution

### Reading the diagrams

A participant is the service that runs it and the component kind or base
inside it, as the system configuration names them, and a topic shows the key
it is partitioned by. Lanes are coloured as in the [system diagram](../diagrams/System%20Diagram.excalidraw): blue for
the API, the message bus and the relays, purple for the processors, orange for
the external adapters, yellow for FDB and grey for the world outside. A ledger
account's code is marked with its type, in the console's colours: 🟧 asset,
🟦 liability, 🟩 equity and 🟥 expense. Each arrow into FDB is one call — a read,
a save, or a changelog or log entry written — and each `critical [transact]`
box is one FDB transaction, holding every call made in it, a read made on its
own included. A box commits where it ends: anything drawn inside it, such as a
publish, happens before the commit, and anything after it once the transaction
has committed. A dashed `ack` back to a topic is the consumer committing its
offset, and a `200` back to the provider the webhook answering, each only once
the consumer has finished: a send drawn before it is covered, since a failure
anywhere earlier leaves the message to be delivered again, and whatever
receives the send takes a second copy as the one it already has. A reply to a
request waiting on it has no `ack`: delivered again it finds no request
waiting, and lost it leaves the request a 5xx, which the client retries with
its key. The sequence diagrams follow a bank on Modulr.

```mermaid
stateDiagram-v2
    [*] --> pending: submit-outbound-payment<br/>reserve in pending-outgoing
    pending --> held: transaction-held (debit)<br/>no money moves
    pending --> completed: transaction-settled (debit)<br/>post the outflow to 🟧 1100
    pending --> failed: transaction-rejected (debit)<br/>release the reservation
    held --> completed: transaction-settled (debit)
    held --> failed: transaction-rejected (debit)
    completed --> returned: transaction-returned (debit)<br/>🟧 1100 back to the debtor
    completed --> [*]
    failed --> [*]
    returned --> [*]
```

- A held, settled or rejected event for a payment already past it is an
  idempotent no-op, and a settlement for a `failed` payment is skipped
  and logged at ERROR.
- A rejection for a `completed` or `returned` payment, a return for one
  that is not `completed`, and any event for a payment that does not
  exist fail the handler and are dead-lettered.

### Submitting and reserving

An outbound payment is submitted in two hops: the API takes the request,
and the payment processor reserves its amount in one transaction.

#### The API takes the request

```mermaid
sequenceDiagram
    box rgba(233, 236, 239, 0.5)
    participant C as Client
    end
    box rgba(165, 216, 255, 0.45)
    participant API as api-service<br/>POST /v1/payments/outbound
    end
    box rgba(255, 236, 153, 0.5)
    participant DB as FDB
    end
    box rgba(165, 216, 255, 0.45)
    participant PC as topic-payments-command<br/>partition-key = debtor account
    participant PR as topic-payments-command-response
    end
    C->>API: submit, Idempotency-Key
    critical transact
    API->>DB: read the idempotency entry for the key
    opt no live entry
    API->>DB: save it, pending
    end
    end
    alt the cache could not be read
    API-->>C: 503
    else the key used before with another body
    API-->>C: 422
    else completed before
    API-->>C: the response recorded, Idempotent-Replayed
    else pending, another request with the key in flight
    API-->>C: 409
    else claimed
    API->>PC: submit-outbound-payment
    PR->>API: the payment processor's reply
    alt 2xx or 4xx
    critical transact
    API->>DB: save the entry, completed, with the response
    end
    else 5xx or no reply
    critical transact
    API->>DB: delete the entry
    end
    end
    API-->>C: 201 pending, 4xx refused, 5xx unknown, retry with the key
    end
```

The 201 says the amount is reserved and the payment is on its way to
the provider, not that it has left the bank: its outcome arrives later,
as the sections below show.

#### The payment processor reserves it

```mermaid
sequenceDiagram
    box rgba(165, 216, 255, 0.45)
    participant PC as topic-payments-command<br/>partition-key = debtor account
    end
    box rgba(208, 191, 255, 0.45)
    participant PP as financial-processors-service<br/>payment/processor
    end
    box rgba(255, 236, 153, 0.5)
    participant DB as FDB
    end
    box rgba(165, 216, 255, 0.45)
    participant PR as topic-payments-command-response
    end
    PC->>PP: submit-outbound-payment, one at a time per partition
    critical transact
    PP->>DB: read the policy stamp, at snapshot
    opt the bank's policies not cached under that stamp
    PP->>DB: read the platform Policies, by label
    PP->>DB: read the bank's PolicyBindings, by PolicyBinding_by_bank
    loop each of the bank's PolicyBindings
    PP->>DB: read the Policy it binds
    end
    end
    opt the bank's providers not cached
    PP->>DB: read the Bank, for its payment provider
    end
    PP->>DB: read the debtor's CashAccount
    opt the ledger account id cache holds their ids
    PP->>DB: read 🟧 1200, 🟧 1100 and 🟥 5100's LedgerAccounts, in one batch, without waiting
    end
    PP->>DB: read 🟧 1200's LedgerAccount
    PP->>DB: read today's OutboundPayment count and sum, at snapshot
    PP->>DB: save Transaction and the two TransactionLegs, in one batch
    PP->>DB: write transaction-posted to the bank's activity log
    PP->>DB: read 🟧 1200, 🟧 1100 and 🟥 5100's LedgerAccounts, in one batch, whose legs write no balance
    PP->>DB: sum the debtor's legs by bucket, at snapshot unless a limit floors its balance
    PP->>DB: read every Balance row of the debtor's account, at snapshot
    opt the debtor's default/pending-outgoing bucket has no row
    PP->>DB: save its Balance row, opened at zero
    end
    PP->>DB: save OutboundPayment, pending
    PP->>DB: write submit to the outbound-payments changelog
    PP->>DB: write outbound-payment-submitted to the bank's activity log
    alt the idempotency key is new
    Note over PP,DB: the transaction commits
    else the key is recorded, a redelivery
    Note over PP,DB: the unique index on the key refuses the save,<br/>and the transaction aborts
    end
    end
    opt the transaction aborted on the key
    critical transact
    PP->>DB: read the OutboundPayment by its idempotency key
    end
    end
    PP->>PR: the payment
    PP-->>PC: ack
```

The submission refuses a scheme the bank's provider does not declare,
then checks the debtor, the capability, the daily count and the instant
and daily amount limits. The bank's policies are read again only when
the policy stamp has moved since they were cached. Where the ledger
account id cache holds their ids, one read fetches 1200, 1100, 5100 and
the debtor's control at once, and each later read of one takes it. It
reserves the amount: a debit on the debtor's `default /
pending-outgoing` bucket, which drops the available balance and leaves
the posted one, against a credit on 1200 pending-outbound. 1200 holds
no row of its own, its balance mirroring every customer's
pending-outgoing bucket, per
[ADR-0038](../adr/0038-an-outbound-submit-writes-no-row-every-payment-shares.md),
and the debtor's bucket is the sum of its legs, per
[ADR-0042](../adr/0042-a-cash-accounts-balance-is-the-sum-of-its-legs.md),
so the submission writes a balance row only when the debtor's bucket
opens, and nothing every payment in the bank shares. The funds check
reads the debtor's sums serializably where a floor bounds the debit, so
two debits on one account conflict. The day's count and sum are read at
snapshot, so the limit can be passed by the submissions in flight at
once. A command
delivered again finds its idempotency key recorded: the transaction
aborts, nothing is written twice, and the reply is the payment the first
delivery recorded.

### Each status change is published

Every transition below writes an `outbound-payments` changelog entry in
the transaction that makes it, and one relay publishes them all.

```mermaid
sequenceDiagram
    box rgba(255, 236, 153, 0.5)
    participant DB as FDB
    end
    box rgba(165, 216, 255, 0.45)
    participant RR as exclusive-dispatchers-service<br/>changelog-relay/runners
    participant PE as topic-payments-event<br/>partition-key = payment
    end
    critical transact
    RR->>DB: read the cursor, outbound-payments-relay
    end
    critical transact
    RR->>DB: read a batch of outbound-payments changelog entries after it, at snapshot
    loop each entry, in commit order
    RR->>PE: outbound-payment-status-changed, once the bus has taken it
    end
    RR->>DB: write the cursor, the last entry read
    end
```

A pass publishes a batch and moves the cursor once, after the last, as
[payments-internal.md](payments-internal.md) describes. The entries
reach the webhook catalogue as `payment.outbound-held`,
`payment.outbound-completed`, `payment.outbound-failed` and
`payment.outbound-returned`.

### Reaching the provider

The submission reaches Modulr in three hops: the bank's activity log
reaches the activity processor, the activity processor sends the
command, and the adapter calls Modulr.

#### The activity log reaches the activity processor

```mermaid
sequenceDiagram
    box rgba(255, 236, 153, 0.5)
    participant DB as FDB
    end
    box rgba(165, 216, 255, 0.45)
    participant AR as exclusive-dispatchers-service<br/>bank-activity/relay
    participant AE as topic-bank-activity-event<br/>partition-key = bank
    end
    box rgba(208, 191, 255, 0.45)
    participant AP as financial-processors-service<br/>payment/activity-event-processor
    end
    critical transact
    AR->>DB: read the cursor, bank-activity-n-relay for log n
    end
    critical transact
    AR->>DB: read a batch of bank-activity-n log entries after it, at snapshot
    loop each entry, in commit order
    AR->>AE: transaction-posted, then outbound-payment-submitted, verbatim, once the bus has taken it
    end
    AR->>DB: write the cursor, the last entry read
    end
    AE->>AP: transaction-posted, then outbound-payment-submitted
```

The submission's `transaction-posted` moves no posted bucket, so under
`balances: per-account` it nets to no provider transfer, as
[payments-internal.md](payments-internal.md) draws its handling.

#### The activity processor sends the payment

```mermaid
sequenceDiagram
    box rgba(255, 236, 153, 0.5)
    participant DB as FDB
    end
    box rgba(165, 216, 255, 0.45)
    participant AE as topic-bank-activity-event<br/>partition-key = bank
    end
    box rgba(208, 191, 255, 0.45)
    participant AP as financial-processors-service<br/>payment/activity-event-processor
    end
    box rgba(165, 216, 255, 0.45)
    participant MC as topic-modulr-command<br/>partition-key = bank
    end
    box rgba(255, 216, 168, 0.5)
    participant AD as external-adapters-service<br/>modulr-adapter/command-processor
    end
    AE->>AP: outbound-payment-submitted
    opt the bank's providers not cached
    critical transact
    AP->>DB: read the bank's providers
    end
    end
    AP->>MC: submit-payment, end-to-end id the payment's id
    AP-->>AE: ack
    MC->>AD: one command at a time
    critical transact
    AD->>DB: save ModulrOutboundIntent kind payment, pending<br/>subject the debtor account
    alt the command's dedup key, the end-to-end id, is new
    Note over AD,DB: the transaction commits
    else the key is recorded, a redelivery
    Note over AD,DB: the unique index on the key refuses the save,<br/>the transaction aborts, and the command is taken as accepted
    end
    end
    AD-->>MC: ack
```

The activity event processor decides from the entry alone and sends on
the bank's provider's command channel, `topic-form3-command` or
`topic-clearbank-command` for a bank on those providers. It records
nothing of its own: an entry delivered again sends the command again,
and the adapter takes the second as the intent it already holds.

#### The adapter calls Modulr

```mermaid
sequenceDiagram
    box rgba(255, 236, 153, 0.5)
    participant DB as FDB
    end
    box rgba(255, 216, 168, 0.5)
    participant IP as external-adapters-service<br/>modulr-relay/outbound-runner
    end
    box rgba(233, 236, 239, 0.5)
    participant PR as Modulr
    end
    critical transact
    IP->>DB: read the oldest 1,000 pending intents, by the status index
    end
    critical transact
    IP->>DB: read the oldest 1,000 sent intents, by the status index
    end
    critical transact
    IP->>DB: read the adapter's breaker
    opt its cool-down over, and no live probe claimed
    IP->>DB: save the breaker, half-open, the probe claimed by this pass
    end
    end
    opt the breaker closed
    critical transact
    IP->>DB: read the breaker, for a failure counted
    end
    end
    alt the breaker open
    loop each pending intent past its maximum age
    critical transact
    IP->>DB: read the intent
    opt still pending
    IP->>DB: save the intent, failed
    IP->>DB: read the outbox for the event's dedup key
    opt not recorded
    IP->>DB: save ModulrOutboxEvent transaction-rejected (debit)
    IP->>DB: write it to the modulr-outbox changelog
    end
    end
    end
    end
    else closed, or this pass holds the probe
    loop each round, while a pending intent may run
    loop each due pending intent no earlier unsent one shares an account with,<br/>at once on the adapter's workers, one only on a probe
    critical transact
    IP->>DB: read the debtor's provider account
    end
    IP->>PR: POST /payments from the debtor's provider account
    opt the call failed, or the breaker has counted a failure
    critical transact
    IP->>DB: read the breaker
    opt the outcome changes it
    IP->>DB: save the breaker
    end
    end
    end
    alt Modulr took the payment
    critical transact
    IP->>DB: read the intent
    opt still pending
    IP->>DB: save the intent, sent, with the provider's payment id
    end
    end
    else Modulr refused it, or its attempts ran out
    critical transact
    IP->>DB: read the intent
    opt still pending
    IP->>DB: save the intent, failed
    IP->>DB: read the outbox for the event's dedup key
    opt not recorded
    IP->>DB: save ModulrOutboxEvent transaction-rejected (debit)
    IP->>DB: write it to the modulr-outbox changelog
    end
    end
    end
    else to be tried again
    critical transact
    IP->>DB: read the intent
    opt still pending
    IP->>DB: save the intent, with its next attempt
    end
    end
    end
    end
    end
    end
    opt the breaker closed, and no call this pass opened it
    loop each sent intent due for reconciliation, at once on the adapter's workers
    IP->>PR: GET /payments, by the provider's payment id
    opt the lookup failed, or the breaker has counted a failure
    critical transact
    IP->>DB: read the breaker
    opt the outcome changes it
    IP->>DB: save the breaker
    end
    end
    end
    critical transact
    IP->>DB: read the intent
    alt Modulr reports it final
    opt still sent
    IP->>DB: save the intent, settled
    IP->>DB: read the outbox for the event's dedup key
    opt not recorded
    IP->>DB: save ModulrOutboxEvent transaction-settled or transaction-rejected
    IP->>DB: write it to the modulr-outbox changelog
    end
    end
    else not final yet
    opt still sent
    IP->>DB: save the intent, with its next reconciliation
    end
    end
    end
    end
    end
```

A pass of the poller reads the oldest 1,000 pending and the oldest 1,000
sent intents the adapter holds, across every bank, by the status index,
and runs them in rounds on the adapter's workers. Each round runs at
once every pending intent that is due and that no earlier intent still
unsent shares an account with, so an account's calls reach
Modulr in the order they were accepted, per
[ADR-0033](../adr/0033-operations-reach-a-provider-in-the-order-they-were-accepted.md),
and the next round takes an account's next call once its earlier one is
sent. Where the sent read stops at its limit, a close or reissue newer
than the last sent intent read waits for a later pass, since an unread
sent intent may share its account. The call pays from the provider
account the adapter recorded when it opened the debtor's account, which
a reissue replaces. While the adapter's breaker is open a pass calls
nothing, as
[ADR-0034](../adr/0034-outbound-calls-go-through-a-breaker-on-their-destination.md)
decides, and fails each pending intent past its maximum age as
undelivered. A refusal, or a call given up, writes
`transaction-rejected` with `failure_kind` `refused` or `undelivered`,
which the rejection path below handles. A sent intent no webhook
settles within the relay's `reconcile-after-ms` is looked up at Modulr,
on the adapter's workers, and a final status is written to the outbox
under the dedup key its webhook would carry, so the payment settles on
the lookup and a late webhook finds it there.

### Settled

#### Modulr reports the settlement

```mermaid
sequenceDiagram
    box rgba(233, 236, 239, 0.5)
    participant PR as Modulr
    end
    box rgba(255, 216, 168, 0.5)
    participant WH as external-adapters-service<br/>modulr-adapter webhook handlers
    end
    box rgba(255, 236, 153, 0.5)
    participant DB as FDB
    end
    PR-->>WH: PAYOUT webhook, status PROCESSED
    critical transact
    WH->>DB: read the intent its external reference names
    end
    critical transact
    WH->>DB: save ModulrOutboxEvent transaction-settled (debit)
    WH->>DB: write it to the modulr-outbox changelog
    WH->>DB: read the intent
    opt still sent
    WH->>DB: save the intent, settled
    end
    alt the event's dedup key is new
    Note over WH,DB: the transaction commits
    else the key is recorded, a webhook delivered again
    Note over WH,DB: the unique index on the key refuses the save,<br/>the transaction aborts, and the event is taken as recorded
    end
    end
    WH-->>PR: 200
```

The event's dedup key is Modulr's payment id and the outcome, the key a
reconciliation writes under, so whichever arrives second is taken as
recorded.

#### The settlement reaches the payment

```mermaid
sequenceDiagram
    box rgba(255, 236, 153, 0.5)
    participant DB as FDB
    end
    box rgba(165, 216, 255, 0.45)
    participant OR as exclusive-dispatchers-service<br/>changelog-relay/runners
    participant SE as topic-schemes-payments-event<br/>partition-key = end-to-end id, else transfer
    end
    box rgba(208, 191, 255, 0.45)
    participant PE as financial-processors-service<br/>payment/event-processor
    end
    critical transact
    OR->>DB: read the cursor, modulr-relay
    end
    critical transact
    OR->>DB: read a batch of modulr-outbox changelog entries after it, at snapshot
    loop each entry, in commit order
    OR->>SE: transaction-settled (debit), once the bus has taken it
    end
    OR->>DB: write the cursor, the last entry read
    end
    SE->>PE: transaction-settled (debit)
    critical transact
    PE->>DB: read the OutboundPayment
    alt pending or held
    PE->>DB: save the OutboundPayment, completed
    PE->>DB: write settle to the outbound-payments changelog
    PE->>DB: read the policy stamp, at snapshot
    opt the bank's policies not cached under that stamp
    PE->>DB: read the platform Policies, by label
    PE->>DB: read the bank's PolicyBindings, by PolicyBinding_by_bank
    loop each of the bank's PolicyBindings
    PE->>DB: read the Policy it binds
    end
    end
    PE->>DB: read the debtor's CashAccount
    opt the ledger account id cache holds their ids
    PE->>DB: read 🟧 1200, 🟧 1100, 🟥 5100 and the debtor's control LedgerAccounts, in one batch, without waiting
    end
    PE->>DB: read 🟧 1200's LedgerAccount
    PE->>DB: read 🟧 1100's LedgerAccount
    PE->>DB: read the control LedgerAccount the debtor's posted leg rolls into
    PE->>DB: save Transaction and the four TransactionLegs, in one batch
    PE->>DB: write transaction-posted to the bank's activity log
    PE->>DB: read 🟧 1200, 🟧 1100 and 🟥 5100's LedgerAccounts, in one batch, whose legs write no balance
    PE->>DB: sum the debtor's legs by bucket, at snapshot
    PE->>DB: read every Balance row of the debtor's account, at snapshot
    opt a bucket a leg reaches has no row
    PE->>DB: save its Balance row, opened at zero
    end
    else completed, a redelivery, or failed or returned
    Note over PE,DB: nothing saved
    else no such payment
    Note over PE,DB: the handler fails
    end
    end
    alt the handler succeeded
    PE-->>SE: ack
    else it failed
    Note over PE,SE: delivered again, then dead-lettered
    end
```

Settlement converts the reservation into the outflow: it credits the
debtor's pending-outgoing bucket and debits its posted one, and debits
1200 against a credit to 1100 cash-at-correspondent. Neither 1200 nor
1100 holds a row, 1100's balance being the sum of its legs, per
[ADR-0039](../adr/0039-cash-at-correspondents-balance-is-the-sum-of-its-legs.md),
and the debtor's two buckets are the sums of theirs, per
[ADR-0042](../adr/0042-a-cash-accounts-balance-is-the-sum-of-its-legs.md),
so settlement writes no balance row unless one of the debtor's buckets
opens. It leaves the available balance as it was, so no limit bounds it
and the debtor's sums are read at snapshot. The transaction
names the debtor as the account the scheme moved the money through, so
its `transaction-posted` entry nets to nothing at a provider holding a
balance per account, which moved the money itself, and the activity
event processor reads and records nothing for it, nor for the
submission's reservation. A settlement for a
`failed` payment is logged at ERROR.

### Rejected

#### Modulr reports the rejection

```mermaid
sequenceDiagram
    box rgba(233, 236, 239, 0.5)
    participant PR as Modulr
    end
    box rgba(255, 216, 168, 0.5)
    participant WH as external-adapters-service<br/>modulr-adapter webhook handlers
    end
    box rgba(255, 236, 153, 0.5)
    participant DB as FDB
    end
    alt PAYMENT_COMPLIANCE_STATUS DECLINED
    PR-->>WH: PAYMENT_COMPLIANCE_STATUS webhook, DECLINED
    WH->>PR: GET /payments, the payment it names
    critical transact
    WH->>DB: save ModulrOutboxEvent transaction-rejected (debit), failure_kind declined
    WH->>DB: write it to the modulr-outbox changelog
    alt the event's dedup key is new
    Note over WH,DB: the transaction commits
    else the key is recorded, a webhook delivered again
    Note over WH,DB: the unique index on the key refuses the save,<br/>the transaction aborts, and the event is taken as recorded
    end
    end
    else a PAYOUT at a failed status
    PR-->>WH: PAYOUT webhook, status failed
    critical transact
    WH->>DB: read the intent its external reference names
    end
    critical transact
    WH->>DB: save ModulrOutboxEvent transaction-rejected (debit), failure_kind declined
    WH->>DB: write it to the modulr-outbox changelog
    WH->>DB: read the intent
    opt still sent
    WH->>DB: save the intent, settled
    end
    alt the event's dedup key is new
    Note over WH,DB: the transaction commits
    else the key is recorded, a webhook delivered again
    Note over WH,DB: the unique index on the key refuses the save,<br/>the transaction aborts, and the event is taken as recorded
    end
    end
    end
    WH-->>PR: 200
```

A call Modulr refused, or one the poller gave up on, writes its
`transaction-rejected` from the poller instead, as
[The adapter calls Modulr](#the-adapter-calls-modulr) draws. A
compliance notification carries no payment detail, so the handler reads
the payment from Modulr before it writes.

#### The rejection reaches the payment

```mermaid
sequenceDiagram
    box rgba(255, 236, 153, 0.5)
    participant DB as FDB
    end
    box rgba(165, 216, 255, 0.45)
    participant OR as exclusive-dispatchers-service<br/>changelog-relay/runners
    participant SE as topic-schemes-payments-event<br/>partition-key = end-to-end id, else transfer
    end
    box rgba(208, 191, 255, 0.45)
    participant PE as financial-processors-service<br/>payment/event-processor
    end
    critical transact
    OR->>DB: read the cursor, modulr-relay
    end
    critical transact
    OR->>DB: read a batch of modulr-outbox changelog entries after it, at snapshot
    loop each entry, in commit order
    OR->>SE: transaction-rejected (debit), once the bus has taken it
    end
    OR->>DB: write the cursor, the last entry read
    end
    SE->>PE: transaction-rejected (debit)
    critical transact
    PE->>DB: read the OutboundPayment
    alt pending or held
    PE->>DB: save the OutboundPayment, failed, with its failure kind and reason code
    PE->>DB: write fail to the outbound-payments changelog
    PE->>DB: read the policy stamp, at snapshot
    opt the bank's policies not cached under that stamp
    PE->>DB: read the platform Policies, by label
    PE->>DB: read the bank's PolicyBindings, by PolicyBinding_by_bank
    loop each of the bank's PolicyBindings
    PE->>DB: read the Policy it binds
    end
    end
    opt their ids cached
    PE->>DB: read 🟧 1200, 🟧 1100 and 🟥 5100's LedgerAccounts, in one batch, without waiting
    end
    PE->>DB: read 🟧 1200's LedgerAccount
    PE->>DB: read the debtor's CashAccount
    PE->>DB: save Transaction, reversing the reservation, and the two TransactionLegs, in one batch
    PE->>DB: write transaction-posted to the bank's activity log
    PE->>DB: read 🟧 1200, 🟧 1100 and 🟥 5100's LedgerAccounts, in one batch, whose legs write no balance
    PE->>DB: sum the debtor's legs by bucket, at snapshot unless a limit caps its balance
    PE->>DB: read every Balance row of the debtor's account, at snapshot
    opt the debtor's default/pending-outgoing bucket has no row
    PE->>DB: save its Balance row, opened at zero
    end
    else failed, a redelivery
    Note over PE,DB: nothing saved
    else completed or returned, or no such payment
    Note over PE,DB: the handler fails
    end
    end
    alt the handler succeeded
    PE-->>SE: ack
    else it failed
    Note over PE,SE: delivered again, then dead-lettered
    end
```

A rejection reverses only a payment still in flight, `pending` or
`held`: it debits 1200 and credits the debtor's pending-outgoing bucket,
releasing the reservation, and records the platform's failure kind and
ISO 20022 reason code, which the API answers as `failure`. Neither leg
is a posted one, so no control is read, and the debtor's bucket, the
sum of its legs, writes no row once open. A rejection is not a return,
so one naming a completed payment fails.

### Held

#### Modulr reports the hold

```mermaid
sequenceDiagram
    box rgba(233, 236, 239, 0.5)
    participant PR as Modulr
    end
    box rgba(255, 216, 168, 0.5)
    participant WH as external-adapters-service<br/>modulr-adapter webhook handlers
    end
    box rgba(255, 236, 153, 0.5)
    participant DB as FDB
    end
    PR-->>WH: PAYMENT_COMPLIANCE_STATUS webhook, HELD
    WH->>PR: GET /payments, the payment it names
    critical transact
    WH->>DB: save ModulrOutboxEvent transaction-held (debit)
    WH->>DB: write it to the modulr-outbox changelog
    alt the event's dedup key is new
    Note over WH,DB: the transaction commits
    else the key is recorded, a webhook delivered again
    Note over WH,DB: the unique index on the key refuses the save,<br/>the transaction aborts, and the event is taken as recorded
    end
    end
    WH-->>PR: 200
```

A release records nothing: the PAYOUT that follows it settles or
rejects the payment.

#### The hold reaches the payment

```mermaid
sequenceDiagram
    box rgba(255, 236, 153, 0.5)
    participant DB as FDB
    end
    box rgba(165, 216, 255, 0.45)
    participant OR as exclusive-dispatchers-service<br/>changelog-relay/runners
    participant SE as topic-schemes-payments-event<br/>partition-key = end-to-end id, else transfer
    end
    box rgba(208, 191, 255, 0.45)
    participant PE as financial-processors-service<br/>payment/event-processor
    end
    critical transact
    OR->>DB: read the cursor, modulr-relay
    end
    critical transact
    OR->>DB: read a batch of modulr-outbox changelog entries after it, at snapshot
    loop each entry, in commit order
    OR->>SE: transaction-held (debit), once the bus has taken it
    end
    OR->>DB: write the cursor, the last entry read
    end
    SE->>PE: transaction-held (debit)
    critical transact
    PE->>DB: read the OutboundPayment
    alt pending
    PE->>DB: save the OutboundPayment, held
    PE->>DB: write hold to the outbound-payments changelog
    else past pending
    Note over PE,DB: nothing saved
    else no such payment
    Note over PE,DB: the handler fails
    end
    end
    alt the handler succeeded
    PE-->>SE: ack
    else it failed
    Note over PE,SE: delivered again, then dead-lettered
    end
```

A provider screening the payment holds it with the money still reserved,
and the settlement or rejection that follows moves it on. A payment
pending or held for 24 hours is reported by `payment/outbound-sweep` in
`exclusive-dispatchers-service`: every five minutes it reads, in a
transaction for each status, at most 1,000 pending and 1,000 held
payments created before now less `report-after-ms`, from the
`OutboundPayment_by_status_created_at` index, and logs each at ERROR.

### Returned after settling

#### Modulr reports the return

```mermaid
sequenceDiagram
    box rgba(233, 236, 239, 0.5)
    participant PR as Modulr
    end
    box rgba(255, 216, 168, 0.5)
    participant WH as external-adapters-service<br/>modulr-adapter webhook handlers
    end
    box rgba(255, 236, 153, 0.5)
    participant DB as FDB
    end
    PR-->>WH: PAYIN webhook, type PO_REV
    opt its source reference names an intent
    critical transact
    WH->>DB: read the intent its source reference names
    end
    end
    opt not yet the adapter's own, and its payment reference names an intent
    critical transact
    WH->>DB: read the intent its payment reference names
    end
    end
    alt one of the adapter's own transfers
    Note over WH: nothing recorded
    else a return
    WH->>PR: GET /payments, the payment its original scheme id names
    opt Modulr names a payment with an external reference
    critical transact
    WH->>DB: read the intent that payment's external reference names
    end
    end
    critical transact
    alt a payment the adapter submitted
    WH->>DB: save ModulrOutboxEvent transaction-returned (debit)
    else no payment of the adapter's
    WH->>DB: save ModulrOutboxEvent transaction-settled (credit)
    end
    WH->>DB: write it to the modulr-outbox changelog
    alt the event's dedup key is new
    Note over WH,DB: the transaction commits
    else the key is recorded, a webhook delivered again
    Note over WH,DB: the unique index on the key refuses the save,<br/>the transaction aborts, and the event is taken as recorded
    end
    end
    end
    WH-->>PR: 200
```

A return the adapter cannot match to a payment it submitted is reported
as money arriving at the account it landed in, so it is never lost, and
takes the inbound path in [payments-inbound.md](payments-inbound.md).

#### The return reaches the payment

```mermaid
sequenceDiagram
    box rgba(255, 236, 153, 0.5)
    participant DB as FDB
    end
    box rgba(165, 216, 255, 0.45)
    participant OR as exclusive-dispatchers-service<br/>changelog-relay/runners
    participant SE as topic-schemes-payments-event<br/>partition-key = end-to-end id, else transfer
    end
    box rgba(208, 191, 255, 0.45)
    participant PE as financial-processors-service<br/>payment/event-processor
    end
    critical transact
    OR->>DB: read the cursor, modulr-relay
    end
    critical transact
    OR->>DB: read a batch of modulr-outbox changelog entries after it, at snapshot
    loop each entry, in commit order
    OR->>SE: transaction-returned (debit), once the bus has taken it
    end
    OR->>DB: write the cursor, the last entry read
    end
    SE->>PE: transaction-returned (debit)
    critical transact
    PE->>DB: read the OutboundPayment
    alt completed
    PE->>DB: save the OutboundPayment, returned, with its reason code and reason
    PE->>DB: write return to the outbound-payments changelog
    PE->>DB: read the policy stamp, at snapshot
    opt the bank's policies not cached under that stamp
    PE->>DB: read the platform Policies, by label
    PE->>DB: read the bank's PolicyBindings, by PolicyBinding_by_bank
    loop each of the bank's PolicyBindings
    PE->>DB: read the Policy it binds
    end
    end
    PE->>DB: read the debtor's CashAccount
    opt their ids cached
    PE->>DB: read 🟧 1200, 🟧 1100, 🟥 5100 and the debtor's control LedgerAccounts, in one batch, without waiting
    end
    PE->>DB: read 🟧 1100's LedgerAccount
    PE->>DB: read the control LedgerAccount the debtor's leg rolls into
    PE->>DB: save Transaction outbound-return and the two TransactionLegs, in one batch
    PE->>DB: write transaction-posted to the bank's activity log
    PE->>DB: read 🟧 1200, 🟧 1100 and 🟥 5100's LedgerAccounts, in one batch, whose legs write no balance
    PE->>DB: sum the debtor's legs by bucket, at snapshot unless a limit caps its balance
    PE->>DB: read every Balance row of the debtor's account, at snapshot
    opt the debtor's default/posted bucket has no row
    PE->>DB: save its Balance row, opened at zero
    end
    else returned, a redelivery
    Note over PE,DB: nothing saved
    else not completed, or no such payment
    Note over PE,DB: the handler fails
    end
    end
    alt the handler succeeded
    PE-->>SE: ack
    else it failed
    Note over PE,SE: delivered again, then dead-lettered
    end
```

The scheme can return a payment after it completed, when the
beneficiary's bank cannot apply it. The return debits 1100 and credits
the debtor's posted bucket by the amount returned, and names the debtor
as the account the scheme moved the money through, so it nets to nothing
at a per-account provider. Like settlement, it writes no balance row
unless the debtor's bucket opens.

### Tests

- **`payment`** — the unsupported scheme, the reservation, the failure
  fields, settlement, reversal, the return transitions and their
  refusals, and each activity entry sent to the bank's provider keyed by
  the bank.
- **`intent-queue`** — an account's calls made in the order they were
  accepted.
- **`<provider>-adapter`** — each provider event mapped to its scheme
  event with the platform's reason code, every amount converted, and an
  unauthenticated delivery refused.
- **`<provider>-relay`** — backoff, refusal, a retry recognised as the
  same request, and reconciliation writing what a late webhook would.
- **`test-api-scenarios`** — the outbound journeys, settled, rejected by
  the scheme and returned after settling, on every provider that carries
  each.

## Alternatives Considered

- **A synchronous call to the provider from the HTTP handler.**
  Rejected: it ties the request to the provider's latency, and a
  failure mid-call leaves the bank's records unknown.

## Known Limitations

- **A submit-payment that cannot be sent waits for the sweep.** The
  activity processor records nothing of its own, so an
  `outbound-payment-submitted` whose send keeps failing is dead-lettered
  once its retries run out and its offset committed past it, leaving the
  payment `pending` until `payment/outbound-sweep` reports it.
- **Every bank's activity shares one partition.** The four activity logs
  are relayed in parallel, but `topic-bank-activity-event` has one
  partition, so every bank's submissions reach
  `payment/activity-event-processor` one at a time.
- **The webhook's lookups skip the breaker.** A compliance notification
  and a returned PAYIN each read the payment from Modulr inside the
  webhook handler, outside the adapter's breaker, so an unreachable
  Modulr fails the notification, which Modulr delivers again.
- **Outbound held then released is not exercised.** The shared test
  values decline every held outbound.
- **ClearBank returns no outbound.** Its adapter and simulator carry no
  returned outbound payment, and its simulator credits a payment to an
  account it has closed.
- **A return reaches a closed account.** A payment returned after its
  debtor account closed credits that account, and the money waits there
  for the bank to move by hand.
- **Settlements queue per partition.** `topic-schemes-payments-event`
  has a partition per `financial-processors-service` replica, each read
  one event at a time, so settlements queue behind those on their own
  partition, as [performance-testing.md](performance-testing.md)
  measures.

## References

- [payments](../prd/payments.md) — the product requirements this design
  serves.
- [payments.md](payments.md) — the provider declaration and the adapter
  contract.
- [payments-internal.md](payments-internal.md) — internal payments, and
  how a relay's pass publishes a batch.
- [payments-inbound.md](payments-inbound.md) — inbound payments.
- [outbound-delivery](outbound-delivery.md) — the intent poller, its
  breaker and reconciliation.
- [chart-of-accounts](chart-of-accounts.md) — 1100 and 1200.
- [performance-testing](performance-testing.md) — the outbound load
  scenario and its ceilings.
- [ADR-0033](../adr/0033-operations-reach-a-provider-in-the-order-they-were-accepted.md)
  — the bank's activity log and the order calls reach a provider in.
- [ADR-0034](../adr/0034-outbound-calls-go-through-a-breaker-on-their-destination.md)
  — the breaker every call to Modulr goes through.
- [ADR-0038](../adr/0038-an-outbound-submit-writes-no-row-every-payment-shares.md)
  — 1200 mirrored from the customers' pending-outgoing balances.
- [ADR-0039](../adr/0039-cash-at-correspondents-balance-is-the-sum-of-its-legs.md)
  — 1100 summed from its legs.
