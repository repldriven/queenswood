# Inbound payments

> **Status: implemented.**

## Objective

Show how an inbound payment, arriving at a Queenswood account through UK
Faster Payments, moves through the platform: the provider's report that
it settled, the checks that decide whether the account takes it, where
the money goes when it cannot, and how a provider that holds, admits or
returns payments changes the path, with the records each FDB
transaction writes.

In scope: an inbound settled, parked in 2500 suspense, held then
released or returned, admitted before it settles, and returned from
suspense to its sender.

Out of scope: the provider declaration and the adapter contract, see
[payments.md](payments.md); internal and outbound payments, see
[payments-internal.md](payments-internal.md) and
[payments-outbound.md](payments-outbound.md); how a policy is evaluated,
see [policy-evaluation.md](policy-evaluation.md); how a balance is
kept, see [chart-of-accounts.md](chart-of-accounts.md).

## Background

- **The report.** The bank's provider tells its adapter of an inbound
  by webhook, and the adapter base's webhook handlers write the scheme
  event to its outbox, which `changelog-relay/runners` in
  `exclusive-dispatchers-service` publishes on
  `topic-schemes-payments-event`, partitioned by the end-to-end id,
  else the transfer.
- **The processors.** `payment/event-processor`, in
  `financial-processors-service`, handles `transaction-settled`,
  `transaction-held`, `transaction-rejected` and `transaction-returned`
  carrying `debit-credit-code` credit in `events/inbound.clj`, and
  `payment/processor` handles `admit-inbound-payment`, which the Form3
  adapter sends unkeyed on `topic-payments-command`.
- **What the provider declares.** `inbound: notified` where the
  provider settles and then tells the platform, `admitted` where it asks
  first; `returns: [inbound]` where the platform may send a payment
  back; `screening: provider` where the provider holds payments while it
  screens them. Modulr and ClearBank are notified and screen, holding
  an inbound they are screening; Form3 is admitted and returning, and
  screens nothing.
- **Statuses.** An inbound payment is `admitted`, `settled`, `held`,
  `suspended` or `returned`.

## Solution

### Reading the diagrams

A participant is the service that runs it and the component kind or base
inside it, as the system configuration names them, and a topic shows the key
it is partitioned by. Lanes are coloured as in the [system diagram](../diagrams/System%20Diagram.excalidraw): blue for
the message bus and the relays, purple for the processors, orange for the
external adapters, yellow for FDB and grey for the world outside. A ledger
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
waiting, and lost it leaves the request unanswered, which the provider asks
again.

```mermaid
stateDiagram-v2
    [*] --> admitted: admit-inbound-payment<br/>opened account, checks pass
    admitted --> settled: transaction-settled (credit)<br/>no checks
    [*] --> settled: transaction-settled (credit)<br/>opened account, checks pass
    [*] --> suspended: transaction-settled (credit)<br/>account not opened, or refused<br/>park in 🟦 2500
    [*] --> held: transaction-held (credit)<br/>opened account, no money moves
    held --> settled: transaction-settled (credit)<br/>release, checks pass
    held --> suspended: transaction-settled (credit)<br/>release refused, park in 🟦 2500
    held --> returned: transaction-rejected (credit)<br/>back to the remitter
    suspended --> returned: transaction-returned (credit)<br/>🟦 2500 to 🟧 1100
    suspended --> suspended: inbound-return-failed<br/>return-failure-reason
    settled --> [*]
    returned --> [*]
    suspended --> [*]
```

- A settlement is deduplicated on `scheme-transaction-id`, and a hold
  matched on end-to-end id, creditor and amount.
- A BBAN matching no account fails the handler and is dead-lettered,
  since every address was issued through the adapter.
- An admission rejected records nothing, since the payment never
  arrived.

### Settled

An inbound settled at a provider that notifies reaches the platform in
three hops: the adapter records the provider's report, the settlement
reaches the payment event processor, which posts it, and the change is
published.

#### Modulr reports it

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
    PR->>WH: PAYIN webhook, signed
    opt the payin carries a source reference, not a move's
    critical transact
    WH->>DB: read the intent the source reference names
    end
    end
    opt the payin carries a payment reference, and no move, transfer or credit was found
    critical transact
    WH->>DB: read the intent the payment reference names
    end
    end
    alt a payin the adapter caused, a transfer's far side, a move or a credit
    Note over WH: nothing recorded, the ledger has it
    else a payin of type PO_REV
    Note over WH,DB: an outbound returned, as payments-outbound.md draws
    else money from outside
    critical transact
    WH->>DB: save ModulrOutboxEvent transaction-settled (credit)
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

The event's dedup key is Modulr's payment id with `:settled` after it,
and its end-to-end id the payment id, which the outbox entry carries as
its ordering key.

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
    OR->>SE: transaction-settled (credit), once the bus has taken it
    end
    OR->>DB: write the cursor, the last entry read
    end
    SE->>PE: transaction-settled (credit)
    critical transact
    PE->>DB: read the CashAccount the creditor's BBAN names
    opt the BBAN names an account
    PE->>DB: read the policy stamp, at snapshot
    opt the bank's policies not cached under the stamp
    PE->>DB: read the platform Policies, by label
    PE->>DB: read the bank's PolicyBindings, by PolicyBinding_by_bank
    loop each of the bank's bindings
    PE->>DB: read the Policy it binds
    end
    end
    end
    PE->>DB: read the InboundPayment by its scheme transaction id
    opt the BBAN names an account
    PE->>DB: read an open admission for the end-to-end id, account and amount
    PE->>DB: read an open hold for the end-to-end id, account and amount
    end
    alt an InboundPayment carries the id, a redelivery
    Note over PE: nothing saved
    else the account is not opened
    Note over PE,DB: parked in suspense, as below
    else an open admission or hold
    Note over PE,DB: settled as the sections below draw
    else no account holds the BBAN
    Note over PE: the handler fails
    else an opened account
    opt their ids cached
    PE->>DB: start loading 🟧 1100, 🟧 1200, 🟥 5100 and the creditor's control, by id
    end
    PE->>DB: read 🟧 1100
    PE->>DB: read today's InboundPayment count, at snapshot
    alt the checks pass
    PE->>DB: read the control LedgerAccount the creditor's leg rolls into
    PE->>DB: save Transaction inbound-transfer and the two TransactionLegs, in one batch
    PE->>DB: write transaction-posted to the bank's activity log
    PE->>DB: read 🟧 1200, 🟧 1100 and 🟥 5100's LedgerAccounts, in one batch, whose legs write no balance
    PE->>DB: sum the creditor's legs by bucket, at snapshot unless a limit caps its balance
    PE->>DB: read every Balance row of the creditor's account, at snapshot
    opt its default/posted bucket opens
    PE->>DB: save its Balance row, opened at zero
    end
    PE->>DB: save InboundPayment, settled
    PE->>DB: write settle to the inbound-payments changelog
    else a check refuses
    Note over PE,DB: parked in suspense, as below
    end
    end
    end
    PE-->>SE: ack
```

The settlement resolves the creditor by BBAN, checks the account is
opened and that the inbound capability and daily count allow it, and
posts DEBIT 1100 / CREDIT creditor. The bank's policies are read again
only when the policy stamp has moved since they were cached, and a
ledger account whose id is cached is read by its id. Neither leg
rewrites a balance row: 1100's balance is the sum of its legs, per
[ADR-0039](../adr/0039-cash-at-correspondents-balance-is-the-sum-of-its-legs.md),
and the creditor's default bucket the sum of its own, per
[ADR-0042](../adr/0042-a-cash-accounts-balance-is-the-sum-of-its-legs.md),
whose row is saved only when the bucket opens. The current account
control is summed from the creditor's legs too. The transaction names
the creditor as the account the scheme moved the money through, so its
`transaction-posted` entry nets to nothing at a provider holding a
balance per account, which credited it itself. A settlement delivered
again finds the payment its scheme transaction id names and saves
nothing. A handler that fails is delivered again, and dead-lettered once
its retries run out.

#### The change is published

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
    RR->>DB: read the cursor, inbound-payments-relay
    end
    critical transact
    RR->>DB: read a batch of inbound-payments changelog entries after it, at snapshot
    loop each entry, in commit order
    RR->>PE: inbound-payment-status-changed, once the bus has taken it
    end
    RR->>DB: write the cursor, the last entry read
    end
```

Every change an inbound payment makes in this document is published
the same way, a pass at a time as
[payments-internal.md](payments-internal.md) describes, and reaches the
webhook catalogue by its change kind: `payment.inbound-settled`,
`payment.inbound-held`, `payment.inbound-released`,
`payment.inbound-suspended` or `payment.inbound-returned`.

### Parked in suspense

#### The settlement parks it

```mermaid
sequenceDiagram
    box rgba(165, 216, 255, 0.45)
    participant SE as topic-schemes-payments-event<br/>partition-key = end-to-end id, else transfer
    end
    box rgba(208, 191, 255, 0.45)
    participant PE as financial-processors-service<br/>payment/event-processor
    end
    box rgba(255, 236, 153, 0.5)
    participant DB as FDB
    end
    SE->>PE: transaction-settled (credit)
    critical transact
    PE->>DB: read the CashAccount the creditor's BBAN names
    PE->>DB: read the policy stamp, at snapshot
    opt the bank's policies not cached under the stamp
    PE->>DB: read the platform Policies, by label
    PE->>DB: read the bank's PolicyBindings, by PolicyBinding_by_bank
    loop each of the bank's bindings
    PE->>DB: read the Policy it binds
    end
    end
    PE->>DB: read the InboundPayment by its scheme transaction id
    PE->>DB: read an open admission for the end-to-end id, account and amount
    PE->>DB: read an open hold for the end-to-end id, account and amount
    alt the account is opened, and a check refuses
    opt their ids cached
    PE->>DB: start loading 🟧 1100, 🟧 1200, 🟥 5100 and the creditor's control, by id
    end
    PE->>DB: read 🟧 1100
    PE->>DB: read today's InboundPayment count, at snapshot
    else the account is not opened
    Note over PE: no checks run
    end
    opt their ids cached
    PE->>DB: start loading 🟧 1100, 🟧 1200 and 🟥 5100, by id
    end
    PE->>DB: read 🟧 1100
    PE->>DB: read 🟦 2500
    PE->>DB: save Transaction inbound-transfer, DEBIT 🟧 1100 and CREDIT 🟦 2500,<br/>and the two TransactionLegs, in one batch
    PE->>DB: write transaction-posted to the bank's activity log
    PE->>DB: read 🟧 1200, 🟧 1100 and 🟥 5100's LedgerAccounts, in one batch, whose legs write no balance
    PE->>DB: read every Balance row of 🟦 2500
    PE->>DB: save 🟦 2500's default/posted Balance row
    PE->>DB: save InboundPayment, suspended with the reason
    PE->>DB: write suspend to the inbound-payments changelog
    PE->>DB: write inbound-payment-suspended to the bank's activity log
    end
    PE-->>SE: ack
```

An inbound the receiving account cannot take, because it is not opened
or a policy refuses it, is kept in the bank's 2500 suspense rather than
lost, recording the ISO 20022 reason as `suspense-reason-code`. 2500
keeps a stored row, which only these rare postings write. Both activity
entries reach `payment/activity-event-processor` as
[payments-internal.md](payments-internal.md) draws. Under
`balances: per-account` the `transaction-posted` entry moves the money
from the receiving account's provider account to the bank's own funds,
as that document describes. Under `returns: [inbound]` the
`inbound-payment-suspended` entry sends it back, as below; otherwise it
stays suspended for the bank to resolve.

### Held, then released or returned

A provider that screens holds an inbound before it settles, ClearBank
here and Modulr through its compliance notification. The platform
records the hold with no money moving, and the settlement or rejection
that follows finds it by end-to-end id, creditor and amount.

#### ClearBank reports it

```mermaid
sequenceDiagram
    box rgba(233, 236, 239, 0.5)
    participant PR as ClearBank
    end
    box rgba(255, 216, 168, 0.5)
    participant WH as external-adapters-service<br/>clearbank-adapter webhook handlers
    end
    box rgba(255, 236, 153, 0.5)
    participant DB as FDB
    end
    alt held for screening
    PR->>WH: inbound-held-transaction webhook, signed
    else released
    PR->>WH: transaction-settled webhook, Credit, signed
    else declined
    PR->>WH: transaction-rejected webhook, Credit, signed
    end
    critical transact
    WH->>DB: save ClearbankOutboxEvent transaction-held, -settled or -rejected (credit)
    WH->>DB: write it to the clearbank-outbox changelog
    alt the event's dedup key is new
    Note over WH,DB: the transaction commits
    else the key is recorded, a webhook delivered again
    Note over WH,DB: the unique index on the key refuses the save,<br/>the transaction aborts, and the event is taken as recorded
    end
    end
    WH-->>PR: 200, echoing the nonce
```

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
    OR->>DB: read the cursor, clearbank-relay
    end
    critical transact
    OR->>DB: read a batch of clearbank-outbox changelog entries after it, at snapshot
    loop each entry, in commit order
    OR->>SE: transaction-held (credit), once the bus has taken it
    end
    OR->>DB: write the cursor, the last entry read
    end
    SE->>PE: transaction-held (credit)
    critical transact
    PE->>DB: read the CashAccount the creditor's BBAN names
    opt the BBAN names an account
    PE->>DB: read any InboundPayment for the end-to-end id, account and amount
    end
    alt one is recorded, a redelivery
    Note over PE: nothing saved
    else no account, or one not opened
    Note over PE: ignored, so the settlement parks in suspense
    else an opened account
    PE->>DB: save InboundPayment, held
    PE->>DB: write hold to the inbound-payments changelog
    end
    end
    PE-->>SE: ack
```

#### The release or the return reaches the payment

```mermaid
sequenceDiagram
    box rgba(165, 216, 255, 0.45)
    participant SE as topic-schemes-payments-event<br/>partition-key = end-to-end id, else transfer
    end
    box rgba(208, 191, 255, 0.45)
    participant PE as financial-processors-service<br/>payment/event-processor
    end
    box rgba(255, 236, 153, 0.5)
    participant DB as FDB
    end
    alt released
    SE->>PE: transaction-settled (credit)
    critical transact
    PE->>DB: read the CashAccount the creditor's BBAN names
    PE->>DB: read the policy stamp, at snapshot
    opt the bank's policies not cached under the stamp
    PE->>DB: read the platform Policies, by label
    PE->>DB: read the bank's PolicyBindings, by PolicyBinding_by_bank
    loop each of the bank's bindings
    PE->>DB: read the Policy it binds
    end
    end
    PE->>DB: read the InboundPayment by its scheme transaction id
    PE->>DB: read an open admission for the end-to-end id, account and amount
    PE->>DB: read the open hold for the end-to-end id, account and amount
    alt the account is opened
    opt their ids cached
    PE->>DB: start loading 🟧 1100, 🟧 1200, 🟥 5100 and the creditor's control, by id
    end
    PE->>DB: read 🟧 1100
    PE->>DB: read today's InboundPayment count, at snapshot
    alt the checks pass, the count without the hold
    PE->>DB: read the control LedgerAccount the creditor's leg rolls into
    PE->>DB: save Transaction inbound-transfer and the two TransactionLegs, in one batch
    PE->>DB: write transaction-posted to the bank's activity log
    PE->>DB: read 🟧 1200, 🟧 1100 and 🟥 5100's LedgerAccounts, in one batch, whose legs write no balance
    PE->>DB: sum the creditor's legs by bucket, at snapshot unless a limit caps its balance
    PE->>DB: read every Balance row of the creditor's account, at snapshot
    opt its default/posted bucket opens
    PE->>DB: save its Balance row, opened at zero
    end
    PE->>DB: save InboundPayment, settled
    PE->>DB: write release to the inbound-payments changelog
    else a check refuses
    Note over PE,DB: the held payment parked in suspense, as above
    end
    else it has stopped being opened
    Note over PE,DB: a new payment parked in suspense, as above,<br/>the hold staying held
    end
    end
    else returned to the remitter
    SE->>PE: transaction-rejected (credit)
    critical transact
    alt the rejection names the creditor's BBAN
    PE->>DB: read the CashAccount the BBAN names
    opt the BBAN names an account
    PE->>DB: read the open hold for the end-to-end id and account
    end
    else it names none
    PE->>DB: read the open holds for the end-to-end id
    end
    alt one hold is open
    PE->>DB: save InboundPayment, returned
    PE->>DB: write return to the inbound-payments changelog
    else none, a redelivery
    Note over PE: nothing saved
    else more than one, and no BBAN to choose by
    Note over PE: the handler fails
    end
    end
    end
    PE-->>SE: ack
```

The release and the return are relayed from the `clearbank-outbox`
changelog as the hold is. A release runs the checks a settlement runs,
counting today's payments without the hold itself, and writes no balance
row where the creditor's bucket is open. A return moves no money: it
never reached the bank.

### Admitted before it settles

Under `inbound: admitted` the provider asks before an inbound settles,
in four hops: Form3 asks the adapter, the payment processor decides,
Form3 settles it, and the settlement reaches the payment.

#### Form3 asks for admission

```mermaid
sequenceDiagram
    box rgba(233, 236, 239, 0.5)
    participant PR as Form3
    end
    box rgba(255, 216, 168, 0.5)
    participant WH as external-adapters-service<br/>form3-adapter webhook handlers
    end
    box rgba(165, 216, 255, 0.45)
    participant PC as topic-payments-command<br/>unkeyed
    participant PS as topic-payments-command-response
    end
    PR->>WH: payment_admission_tasks created, unsigned
    WH->>PR: GET the task
    WH->>PR: GET the payment
    alt the task is pending and the bank's to complete
    WH->>PC: admit-inbound-payment
    alt a reply in time
    PS->>WH: the payment processor's reply
    WH->>PR: PATCH the task, passed or failed with the reason
    WH-->>PR: 200
    else no reply in time
    WH-->>PR: 500, so Form3 asks again
    end
    else anything else
    WH-->>PR: 200
    end
```

#### The payment processor decides

```mermaid
sequenceDiagram
    box rgba(165, 216, 255, 0.45)
    participant PC as topic-payments-command<br/>unkeyed
    end
    box rgba(208, 191, 255, 0.45)
    participant PP as financial-processors-service<br/>payment/processor
    end
    box rgba(255, 236, 153, 0.5)
    participant DB as FDB
    end
    box rgba(165, 216, 255, 0.45)
    participant PS as topic-payments-command-response
    end
    PC->>PP: admit-inbound-payment
    critical transact
    PP->>DB: read the CashAccount the creditor's BBAN names
    opt the BBAN names an account
    PP->>DB: read any InboundPayment for the end-to-end id, account and amount
    end
    alt one is recorded, a redelivery
    Note over PP: admitted, nothing saved
    else no account, or one not opened
    Note over PP: rejected, AC01, AC04 or AC06
    else an opened account
    PP->>DB: read the policy stamp, at snapshot
    opt the bank's policies not cached under the stamp
    PP->>DB: read the platform Policies, by label
    PP->>DB: read the bank's PolicyBindings, by PolicyBinding_by_bank
    loop each of the bank's bindings
    PP->>DB: read the Policy it binds
    end
    end
    PP->>DB: read today's InboundPayment count, at snapshot
    alt the checks pass
    PP->>DB: save InboundPayment, admitted
    PP->>DB: write admit to the inbound-payments changelog
    else a check refuses
    Note over PP: rejected, AG01
    end
    end
    end
    PP->>PS: admitted, or rejected with the reason
    PP-->>PC: ack
```

#### Form3 settles it

```mermaid
sequenceDiagram
    box rgba(233, 236, 239, 0.5)
    participant PR as Form3
    end
    box rgba(255, 216, 168, 0.5)
    participant WH as external-adapters-service<br/>form3-adapter webhook handlers
    end
    box rgba(255, 236, 153, 0.5)
    participant DB as FDB
    end
    PR->>WH: payment_admissions updated, unsigned
    WH->>PR: GET the admission
    WH->>PR: GET the payment
    alt the admission is confirmed
    critical transact
    WH->>DB: save Form3OutboxEvent transaction-settled (credit)
    WH->>DB: write it to the form3-outbox changelog
    alt the event's dedup key is new
    Note over WH,DB: the transaction commits
    else the key is recorded, a notification delivered again
    Note over WH,DB: the unique index on the key refuses the save,<br/>the transaction aborts, and the event is taken as recorded
    end
    end
    else it failed
    Note over WH: nothing recorded
    end
    WH-->>PR: 200
```

#### The admitted settlement reaches the payment

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
    OR->>DB: read the cursor, form3-relay
    end
    critical transact
    OR->>DB: read a batch of form3-outbox changelog entries after it, at snapshot
    loop each entry, in commit order
    OR->>SE: transaction-settled (credit), once the bus has taken it
    end
    OR->>DB: write the cursor, the last entry read
    end
    SE->>PE: transaction-settled (credit)
    critical transact
    PE->>DB: read the CashAccount the creditor's BBAN names
    PE->>DB: read the policy stamp, at snapshot
    opt the bank's policies not cached under the stamp
    PE->>DB: read the platform Policies, by label
    PE->>DB: read the bank's PolicyBindings, by PolicyBinding_by_bank
    loop each of the bank's bindings
    PE->>DB: read the Policy it binds
    end
    end
    PE->>DB: read the InboundPayment by its scheme transaction id
    PE->>DB: read the open admission for the end-to-end id, account and amount
    PE->>DB: read an open hold for the end-to-end id, account and amount
    alt the account is still opened
    opt their ids cached
    PE->>DB: start loading 🟧 1100, 🟧 1200, 🟥 5100 and the creditor's control, by id
    end
    PE->>DB: read 🟧 1100
    PE->>DB: read the control LedgerAccount the creditor's leg rolls into
    PE->>DB: save Transaction inbound-transfer and the two TransactionLegs, in one batch
    PE->>DB: write transaction-posted to the bank's activity log
    PE->>DB: read 🟧 1200, 🟧 1100 and 🟥 5100's LedgerAccounts, in one batch, whose legs write no balance
    PE->>DB: sum the creditor's legs by bucket, at snapshot unless a limit caps its balance
    PE->>DB: read every Balance row of the creditor's account, at snapshot
    opt its default/posted bucket opens
    PE->>DB: save its Balance row, opened at zero
    end
    PE->>DB: save InboundPayment, settled
    PE->>DB: write settle to the inbound-payments changelog
    else it has stopped being opened
    Note over PE,DB: the admitted payment parked in suspense, as above
    end
    end
    PE-->>SE: ack
```

Form3's notifications carry no signature, so the adapter reads each
resource they name back from Form3 with a signed call before acting on
it. It sends the admission unkeyed and waits for `payment`'s answer,
and where none comes in time answers Form3 500, so Form3 asks again
until its own deadline fails the admission. `payment` admits where the
BBAN names an opened account and the checks a settlement runs pass, and
rejects otherwise: `AC01` for a BBAN matching no account, `AC04` for
one closed, `AC06` for one not opened, `AG01` for a payment a policy
refuses. The business-day counts include admitted payments, so two
admitted at once cannot pass one limit between them. An admission
redelivered for a payment already recorded answers admitted and records
nothing. The settlement runs no checks again.

### Returned from suspense

Under `returns: [inbound]` a suspended inbound goes back to its sender
in three hops: the activity processor sends the return, the adapter
sends it to Form3 and learns what became of it, and the outcome reaches
the payment. The `inbound-payment-suspended` entry reaches the activity
processor as [payments-internal.md](payments-internal.md) draws for
`transaction-posted`.

#### The activity processor sends the return

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
    participant FC as topic-form3-command<br/>partition-key = bank
    end
    box rgba(255, 216, 168, 0.5)
    participant AD as external-adapters-service<br/>form3-adapter/command-processor
    end
    AE->>AP: inbound-payment-suspended
    opt the bank's providers not cached
    critical transact
    AP->>DB: read the bank's providers
    end
    end
    alt the bank's provider declares returns: [inbound]
    AP->>FC: return-payment, with the suspense reason
    else no inbound returns
    Note over AP: nothing sent, the payment stays suspended
    end
    AP-->>AE: ack
    FC->>AD: one command at a time
    critical transact
    AD->>DB: save Form3OutboundIntent kind return, pending, no subjects
    alt the command's dedup key is new
    Note over AD,DB: the transaction commits
    else the key is recorded, a redelivery
    Note over AD,DB: the unique index on the key refuses the save,<br/>the transaction aborts, and the command is taken as accepted
    end
    end
    AD-->>FC: ack
```

The command's dedup key is `return:` and the payment's id, so a
redelivered entry is the intent the adapter already holds. A return
names no account as a subject, so it waits behind no other call.

#### The adapter sends the return and asks what became of it

```mermaid
sequenceDiagram
    box rgba(255, 236, 153, 0.5)
    participant DB as FDB
    end
    box rgba(255, 216, 168, 0.5)
    participant IP as external-adapters-service<br/>form3-relay/outbound-runner
    end
    box rgba(233, 236, 239, 0.5)
    participant PR as Form3
    end
    critical transact
    IP->>DB: read the oldest 1,000 pending intents, by the status index
    end
    critical transact
    IP->>DB: read the oldest 1,000 sent intents, by the status index
    end
    opt an intent was read
    critical transact
    IP->>DB: read the adapter's breaker, claiming the probe when half-open
    end
    alt the breaker open
    loop each pending intent past its maximum age
    critical transact
    IP->>DB: read the intent
    IP->>DB: save the intent, failed
    IP->>DB: read the outbox for inbound-return-failed's dedup key
    IP->>DB: save Form3OutboxEvent inbound-return-failed
    IP->>DB: write it to the form3-outbox changelog
    end
    end
    else closed, or half-open for one probe
    opt the breaker closed
    critical transact
    IP->>DB: read the breaker, for a failure counted
    end
    end
    loop each due pending intent no earlier unsent one shares an account with, in rounds on the adapter's workers, one only when half-open
    IP->>PR: POST the return
    IP->>PR: POST its submission
    opt the call failed, or the breaker has counted a failure
    critical transact
    IP->>DB: read the breaker
    IP->>DB: save the breaker, with the call's outcome
    end
    end
    critical transact
    IP->>DB: read the intent
    alt Form3 took it
    IP->>DB: save the intent, sent, to ask after reconcile-after-ms
    else no answer, attempts left
    IP->>DB: save the intent, pending, its next attempt later
    else Form3 refused it, or its attempts ran out
    IP->>DB: save the intent, failed
    IP->>DB: read the outbox for inbound-return-failed's dedup key
    IP->>DB: save Form3OutboxEvent inbound-return-failed
    IP->>DB: write it to the form3-outbox changelog
    end
    end
    end
    opt the breaker closed, and no call opened it
    loop each sent return due to be asked after, at once on the adapter's workers
    IP->>PR: GET the return's submission
    opt the lookup failed, or the breaker has counted a failure
    critical transact
    IP->>DB: read the breaker
    IP->>DB: save the breaker, with the lookup's outcome
    end
    end
    critical transact
    IP->>DB: read the intent
    alt delivered
    IP->>DB: save the intent, settled
    IP->>DB: read the outbox for transaction-returned's dedup key
    IP->>DB: save Form3OutboxEvent transaction-returned (credit)
    IP->>DB: write it to the form3-outbox changelog
    else not delivered
    IP->>DB: save the intent, failed
    IP->>DB: read the outbox for inbound-return-failed's dedup key
    IP->>DB: save Form3OutboxEvent inbound-return-failed
    IP->>DB: write it to the form3-outbox changelog
    else not final yet
    IP->>DB: save the intent, sent, to ask again later
    end
    end
    end
    end
    end
    end
```

A pass reads at most `pass-limit`, 1,000 by default, of each status,
oldest first. Each save of the intent is made only where the intent
still has the status the pass read it in, and an outbox event only where
its dedup key is not already recorded.

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
    OR->>DB: read the cursor, form3-relay
    end
    critical transact
    OR->>DB: read a batch of form3-outbox changelog entries after it, at snapshot
    loop each entry, in commit order
    OR->>SE: transaction-returned (credit) or inbound-return-failed, once the bus has taken it
    end
    OR->>DB: write the cursor, the last entry read
    end
    alt returned
    SE->>PE: transaction-returned (credit)
    critical transact
    PE->>DB: read the InboundPayment by its scheme transaction id
    alt suspended
    PE->>DB: read the policy stamp, at snapshot
    opt the bank's policies not cached under the stamp
    PE->>DB: read the platform Policies, by label
    PE->>DB: read the bank's PolicyBindings, by PolicyBinding_by_bank
    loop each of the bank's bindings
    PE->>DB: read the Policy it binds
    end
    end
    opt their ids cached
    PE->>DB: start loading 🟧 1100, 🟧 1200 and 🟥 5100, by id
    end
    PE->>DB: read 🟧 1100
    PE->>DB: read 🟦 2500
    PE->>DB: save Transaction inbound-return, DEBIT 🟦 2500 and CREDIT 🟧 1100,<br/>and the two TransactionLegs, in one batch
    PE->>DB: write transaction-posted to the bank's activity log
    PE->>DB: read 🟧 1200, 🟧 1100 and 🟥 5100's LedgerAccounts, in one batch, whose legs write no balance
    PE->>DB: read every Balance row of 🟦 2500
    PE->>DB: save 🟦 2500's default/posted Balance row
    PE->>DB: save InboundPayment, returned
    PE->>DB: write return to the inbound-payments changelog
    else returned already, a redelivery
    Note over PE: nothing saved
    else none, or not suspended
    Note over PE: the handler fails
    end
    end
    else not returned
    SE->>PE: inbound-return-failed
    critical transact
    PE->>DB: read the InboundPayment by its scheme transaction id
    alt suspended, no failure recorded
    PE->>DB: save InboundPayment, suspended with the return-failure-reason
    PE->>DB: write return-failed to the inbound-payments changelog
    else recorded already, or no longer suspended
    Note over PE: nothing saved
    else none
    Note over PE: the handler fails
    end
    end
    end
    PE-->>SE: ack
```

`transaction-returned` (credit) is deduplicated on Form3's id for the
inbound, and empties suspense of the payment. A return the provider
refuses or does not deliver is reported as `inbound-return-failed`, and
the payment stays suspended carrying it as `return-failure-reason`.

### Tests

- **`payment`** — settlement and its checks, parking in suspense,
  holds, admission and each reason it rejects with, and the return
  transitions and their refusals.
- **`<provider>-adapter`** — each provider event mapped to its scheme
  event, every amount converted, an unauthenticated delivery refused,
  and, for Form3, admission answered from `payment`'s reply.
- **`<provider>-simulator`** — `/simulate/inbound-payment` firing a
  settlement, or a hold that settles or returns, and on a simulator
  that asks for admission, sending the request first.
- **`test-api-scenarios`** — the inbound journeys, settled, held then
  released or returned, on every provider that carries each, with an
  admission admitted and one refused, and an inbound returned.

## Alternatives Considered

- **Returning money a policy refuses, whatever the provider.**
  Rejected: a provider that only notifies has no return instruction,
  so parking stays the path wherever `returns` lacks `inbound`.
- **The adapter deciding an admission from the query bricks.**
  Rejected: the limit checks and business-day counts are `payment`'s,
  and a second copy in the adapter would drift from the one a
  settlement applies.
- **Admitting every inbound and parking what cannot be applied.**
  Rejected under `inbound: admitted`: where the scheme asks, refusing
  at the door keeps money the bank cannot apply out of suspense.

## Known Limitations

- **A suspended inbound is never resolved** except by a return under
  `returns: [inbound]`.
- **A return the provider refuses stays in suspense.** The adapter logs
  it and the payment stays `suspended` for the bank to resolve by hand.
- **A return is reported by asking.** The provider's notification of a
  delivered return names no payment, so the adapter learns of it when
  it next reconciles, after `reconcile-after-ms`.
- **A return waits on every bank's activity.** `topic-bank-activity-event`
  has one partition, so an `inbound-payment-suspended` entry reaches the
  activity processor behind every bank's entries, as
  [payments-internal.md](payments-internal.md) records.
- **An admission not answered in time is refused.** The adapter answers
  Form3 500 until Form3's own deadline fails the admission, and the
  sender's bank tells its customer.
- **An admission answered after the deadline is lost.** Form3 fails the
  admission, and a payment the platform admitted by then stays
  `admitted`.
- **An admitted inbound the scheme never settles stays `admitted`.**
- **Identical holds are one hold.**
- **Settlement order is the provider's.** A settlement delivered before
  its hold settles as a new inbound and leaves the hold `held`.
- **Money arriving as an account closes refuses the close.** An inbound
  the provider takes between a close being accepted and carried out
  leaves a per-account provider refusing the close for its balance: the
  account returns to where it closed from, the inbound is parked, and a
  second close succeeds once the parked money has moved to own funds.
- **Nothing is screened under `screening: bank`.** No payment is held
  until a screening design exists, so an installation on rails screens
  outside the platform.

## References

- [payments](../prd/payments.md) — the product requirements this design
  serves.
- [payments.md](payments.md) — the provider declaration and the adapter
  contract.
- [payments-internal.md](payments-internal.md) — internal payments, and
  the mirroring a parked inbound takes.
- [payments-outbound.md](payments-outbound.md) — outbound payments.
- [policy-evaluation](policy-evaluation.md) — the checks an inbound
  runs.
- [chart-of-accounts](chart-of-accounts.md) — 1100 and 2500.
- [ADR-0034](../adr/0034-outbound-calls-go-through-a-breaker-on-their-destination.md)
  — the breaker every call to Form3 goes through.
- [ADR-0039](../adr/0039-cash-at-correspondents-balance-is-the-sum-of-its-legs.md)
  — 1100 summed from its legs.
- [ISO 20022 external code sets](https://www.iso20022.org/catalogue-messages/additional-content-messages/external-code-sets)
  — the reason codes an admission rejects with.
