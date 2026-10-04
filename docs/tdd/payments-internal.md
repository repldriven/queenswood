# Internal payments

> **Status: implemented.**

## Objective

Show how an internal payment, from one Queenswood account to another in
the same bank, moves through the platform: the request, the one FDB
transaction that records and posts it, and, where the bank's provider
holds a balance for each account, the transfer that makes the provider
hold what the ledger does.

In scope: `submit-internal-payment` from the API to its reply, the
records each transaction writes, the bank's activity entry it leaves,
and the provider transfer that follows it on a provider declaring
`balances: per-account`.

Out of scope: the provider declaration and the adapter contract, see
[payments.md](payments.md); outbound and inbound payments, see
[payments-outbound.md](payments-outbound.md) and
[payments-inbound.md](payments-inbound.md); how a balance is kept, see
[chart-of-accounts.md](chart-of-accounts.md); how a policy is evaluated,
see [policy-evaluation.md](policy-evaluation.md).

## Background

- **The request.** `POST /v1/payments/internal` in `bases/api`'s
  `payment` routes sends `submit-internal-payment` on
  `topic-payments-command`, partitioned by the debtor account,
  and answers 201 from the reply on `topic-payments-command-response`.
- **The processor.** `payment/processor`, in
  `financial-processors-service`, handles the command in `core.clj`
  `submit-internal`.
- **The bank's activity.** `transaction/record-transaction` records a
  `transaction-posted` entry on the bank's activity log, in the
  transaction that posts. There are four activity logs,
  `bank-activity-0` to `bank-activity-3`, and a bank's id picks the one
  all its entries go to. `bank-activity/relay`, a runner per log in
  `exclusive-dispatchers-service`, publishes it on
  `topic-bank-activity-event`, partitioned by bank, where
  `payment/activity-event-processor` reads it.
- **Mirroring.** `payment`'s `events/provider_transfer.clj` turns a
  posted transaction into `ProviderTransfer` records and
  `transfer-between-accounts` commands under `balances: per-account`,
  as [payments.md](payments.md) describes.

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
its key.

```mermaid
stateDiagram-v2
    state "ProviderTransfer, one per netted pair" as PT {
        [*] --> pending: transaction-posted<br/>netted and recorded
        pending --> completed: transfer-completed
        pending --> failed: transfer-failed<br/>logged at ERROR, the ledger stands
        completed --> [*]
        failed --> [*]
    }
    [*] --> posted: submit-internal-payment<br/>debit the debtor, credit the creditor
    posted --> PT: balances per-account<br/>mirrored at the provider
    posted --> [*]: balances pooled<br/>nothing moves at the provider
    PT --> [*]
```

- An internal payment has no status of its own: it is posted in the
  transaction that submits it, and its mirror is what moves.
- An outcome for a transfer no longer `pending` is a no-op, and one for
  a transfer that does not exist fails the handler and is dead-lettered.

### Submitting and settling

An internal payment is submitted and settled in three hops: the API
takes the request, the payment processor settles it in one transaction,
and the settlement is published.

#### The API takes the request

```mermaid
sequenceDiagram
    box rgba(233, 236, 239, 0.5)
    participant C as Client
    end
    box rgba(165, 216, 255, 0.45)
    participant API as api-service<br/>POST /v1/payments/internal
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
    alt completed before
    API-->>C: the response recorded, Idempotent-Replayed
    else pending, another request with the key in flight
    API-->>C: 409
    else the key used before with another body
    API-->>C: 422
    else claimed
    API->>PC: submit-internal-payment
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
    API-->>C: 201 posted, 4xx refused, 5xx unknown, retry with the key
    end
```

The key is claimed before the command is sent, so two requests with one
key send one command between them. A request the platform failed to
answer releases its claim, and the client may send it again.

#### The payment processor settles it

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
    PC->>PP: submit-internal-payment, one at a time per partition
    critical transact
    PP->>DB: read the policy stamp, at snapshot
    PP->>DB: read the debtor's CashAccount
    PP->>DB: read the creditor's CashAccount
    PP->>DB: read today's InternalPayment count, at snapshot
    PP->>DB: read the control LedgerAccount each leg rolls into
    PP->>DB: save Transaction
    PP->>DB: save the two TransactionLegs
    PP->>DB: write transaction-posted to the bank's activity log
    PP->>DB: read every Balance of the debtor's account
    PP->>DB: read every Balance of the creditor's account
    PP->>DB: save the debtor's default/posted Balance
    PP->>DB: save the creditor's default/posted Balance
    PP->>DB: save InternalPayment
    PP->>DB: write settle to the internal-payments changelog
    alt the idempotency key is new
    Note over PP,DB: the transaction commits
    else the key is recorded, a redelivery
    Note over PP,DB: the unique index on the key refuses the save,<br/>and the transaction aborts
    end
    end
    opt the transaction aborted on the key
    critical transact
    PP->>DB: read the InternalPayment by its idempotency key
    end
    end
    PP->>PR: the payment
    PP-->>PC: ack
```

The command's one FDB transaction checks both accounts are operable and
in the payment's currency, the capability and the daily count,
`ensure-controls` resolving the control each leg's product type rolls
into, and then records and posts. The bank's policies are read again
only when the policy stamp has moved since they were cached. Its two
legs debit the debtor's and credit the creditor's `default / posted`
buckets, which are the only balance rows it writes: the deposit
controls 2100, 2200, 2300 and 3100 are summed from those rows, per
[ADR-0037](../adr/0037-a-control-accounts-balance-is-the-sum-of-the-balances-that-roll-into-it.md),
and carry no leg. A command delivered again finds its idempotency key
recorded: the transaction aborts, nothing is written twice, and the
reply is the payment the first delivery recorded.

#### The settlement is published

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
    RR->>DB: read the cursor, internal-payments-relay
    end
    critical transact
    RR->>DB: read a batch of internal-payments changelog entries after it, at snapshot
    loop each entry, in commit order
    RR->>PE: internal-payment-settled, once the bus has taken it
    end
    RR->>DB: write the cursor, the last entry read
    end
```

A pass publishes a batch, at most the 500 entries `fdb/process-changelog`
reads by default, which the relay does not configure, and moves the
cursor once, after the last: a publish waits for the bus to take it, one
that fails aborts the pass, and the next pass publishes the batch again,
the entries already published included. The cursor is read in a
transaction of its own and written without a conflict check, so one
runner owns it. The changelog entry reaches the webhook catalogue as
`payment.internal-settled`.

### Mirroring at the provider

On a provider that declares `balances: per-account`, as Modulr does, a
posted transaction is mirrored at the provider in four hops, each drawn
below between the service that hands it on, the topic or call that
carries it, and the service that takes it up. On one that declares
`balances: pooled`, as Form3 and ClearBank do, the activity processor
sends nothing: the provider holds one balance for all the bank's
accounts, and only the ledger says how much of it is each account's, so
a payment between two of them moves nothing at the provider.

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
    AR->>AE: transaction-posted, verbatim, once the bus has taken it
    end
    AR->>DB: write the cursor, the last entry read
    end
    AE->>AP: transaction-posted
```

The relay publishes before its cursor commits, so a pass that fails
after publishing publishes the same entries again, and the processor
takes each entry as many times as it arrives.

#### The activity processor sends the transfer

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
    AE->>AP: transaction-posted
    opt the bank's providers not cached
    critical transact
    AP->>DB: read the bank's providers
    end
    end
    alt the bank's provider declares balances: per-account
    critical transact
    AP->>DB: read the ProviderTransfers already recorded for the transaction
    alt none recorded
    opt 🟧 1100 and the own-funds account not cached
    AP->>DB: read 🟧 1100 and the own-funds CashAccount
    end
    AP->>DB: read the CashAccount behind each leg
    AP->>DB: save a ProviderTransfer, pending, for each pair
    else recorded, a redelivery
    Note over AP: the recorded transfers, nothing saved
    end
    end
    loop each ProviderTransfer still pending
    AP->>MC: transfer-between-accounts, debtor and creditor accounts
    end
    else balances: pooled
    Note over AP: nothing recorded or sent
    end
    AP-->>AE: ack
    MC->>AD: one command at a time
    critical transact
    AD->>DB: save ModulrOutboundIntent kind transfer, pending<br/>subjects the debtor and creditor accounts
    alt the command's dedup key is new
    Note over AD,DB: the transaction commits
    else the key is recorded, a redelivery
    Note over AD,DB: the unique index on the key refuses the save,<br/>the transaction aborts, and the command is taken as accepted
    end
    end
    AD-->>MC: ack
```

The activity event processor nets the transaction's posted default legs
per party: the debtor's cash account owes, the creditor's is owed, and
the pair becomes one `ProviderTransfer`, unique on transaction and pair.

A `transaction-posted` delivered again, because a send failed or the
process stopped before the ack, finds its transfers recorded, saves
nothing, and sends again those still pending. Where the first send got
through, the adapter receives the command twice and takes the second as
the intent it already holds, unique on the command's dedup key, so
Modulr is called once.

#### The adapter calls Modulr and hears back

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
    box rgba(255, 216, 168, 0.5)
    participant WH as external-adapters-service<br/>modulr-adapter webhook handlers
    end
    critical transact
    IP->>DB: read every pending intent
    end
    critical transact
    IP->>DB: read every sent intent
    end
    critical transact
    IP->>DB: read the adapter's breaker, claiming the probe when half-open
    end
    opt the breaker closed
    critical transact
    IP->>DB: read the breaker, for a failure counted
    end
    end
    loop each due pending intent no earlier unsent one shares an account with, on the adapter's workers
    critical transact
    IP->>DB: read the debtor's provider account
    end
    critical transact
    IP->>DB: read the creditor's provider account
    end
    IP->>PR: POST a payment between the two provider accounts
    opt the call failed, or the breaker has counted a failure
    critical transact
    IP->>DB: read the breaker
    IP->>DB: save the breaker, with the call's outcome
    end
    end
    critical transact
    IP->>DB: read the intent
    IP->>DB: save the intent, sent
    end
    end
    PR-->>WH: PAYOUT webhook, status PROCESSED
    critical transact
    WH->>DB: read the intent the payment answers
    end
    critical transact
    WH->>DB: save ModulrOutboxEvent transfer-completed
    WH->>DB: write it to the modulr-outbox changelog
    WH->>DB: read the sent intent
    WH->>DB: save the intent, settled
    alt the event's dedup key is new
    Note over WH,DB: the transaction commits
    else the key is recorded, a webhook delivered again
    Note over WH,DB: the unique index on the key refuses the save,<br/>the transaction aborts, and the event is taken as recorded
    end
    end
    WH-->>PR: 200
```

A pass of the poller reads every intent the adapter holds, across every
bank, and runs at once each pending one that is due and that no earlier
intent still unsent shares an account with. The transfer's intent holds
both accounts as its subjects, so a later call touching either waits
until this one is sent, as
[ADR-0033](../adr/0033-operations-reach-a-provider-in-the-order-they-were-accepted.md)
decides. The call resolves the transfer's cash accounts to their
provider accounts, the Modulr accounts the adapter recorded when it
opened them. While the adapter's breaker is open a pass calls nothing,
as
[ADR-0034](../adr/0034-outbound-calls-go-through-a-breaker-on-their-destination.md)
decides. The webhook answers 200 only once its outbox entry commits, so
Modulr sends it again until it has.

#### The outcome reaches the payment

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
    OR->>SE: transfer-completed, once the bus has taken it
    end
    OR->>DB: write the cursor, the last entry read
    end
    SE->>PE: transfer-completed
    critical transact
    PE->>DB: read the ProviderTransfer
    alt still pending
    PE->>DB: save the ProviderTransfer, completed
    else finished, a redelivery
    Note over PE: nothing saved
    end
    end
    PE-->>SE: ack
```

A `transfer-failed` leaves the ledger as it is and logs the transaction
id at ERROR for the bank to reconcile.

### Tests

- **`payment`** — the checks an internal payment runs, the transaction
  and legs it records, netting a transaction's legs into provider
  transfers, and an entry a pooled provider does not act on sending
  nothing.
- **`intent-queue`** — a transfer holding both its accounts.
- **`modulr-relay`** — a transfer naming the provider accounts its
  opens recorded.
- **`test-scenarios`** — internal payments compared with the model, and
  every simulated provider balance checked against the ledger after
  each mirrored flow.

## Alternatives Considered

- **Internal payments two-phase, settling when the provider moves the
  money.** Rejected: an internal payment settles at once for the
  customer, and a mirror failing is the bank's reconciliation, not the
  customer's payment.
- **Mirroring from each brick that posts.** Rejected: `interest`,
  `reward` and `payment` would each learn the provider's balance model,
  where one consumer of the bank's activity sees every posting.

## Known Limitations

- **A failed mirror leaves the balances apart.** Nothing retries a
  failed `ProviderTransfer` or compares the provider's balances with the
  ledger. A `transaction-posted` whose handler keeps failing, as when the
  command cannot be sent, is dead-lettered once its retries run out and
  its offset committed past it, leaving its `ProviderTransfer` pending.
- **A bank's activity is serial.** A bank's activity log is read by one
  relay runner and its entries by one consumer, so one bank's mirrors
  are sent in order and at the rate that consumer reaches.
- **Every bank's activity shares one partition.** The four activity logs
  are relayed in parallel, but `topic-bank-activity-event` has one
  partition, so every bank's entries reach
  `payment/activity-event-processor` one at a time. More partitions,
  keyed by bank as the topic already is, would carry the logs'
  parallelism through to the processor.

## References

- [payments](../prd/payments.md) — the product requirements this design
  serves.
- [payments.md](payments.md) — the provider declaration, the adapter
  contract and balances at the provider.
- [payments-outbound.md](payments-outbound.md) — outbound payments.
- [payments-inbound.md](payments-inbound.md) — inbound payments.
- [chart-of-accounts](chart-of-accounts.md) — the controls an internal
  payment's legs roll into.
- [outbound-delivery](outbound-delivery.md) — the intent poller and its
  breaker.
- [ADR-0033](../adr/0033-operations-reach-a-provider-in-the-order-they-were-accepted.md)
  — the bank's activity log and the order calls reach a provider in.
- [ADR-0034](../adr/0034-outbound-calls-go-through-a-breaker-on-their-destination.md)
  — the breaker every call to Modulr goes through.
- [ADR-0037](../adr/0037-a-control-accounts-balance-is-the-sum-of-the-balances-that-roll-into-it.md)
  — controls summed from the customer rows.
