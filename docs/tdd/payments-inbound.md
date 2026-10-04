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
  `topic-schemes-payments-event`, two partitions, keyed by the payment.
- **The processors.** `payment/event-processor`, in
  `financial-processors-service`, handles `transaction-settled`,
  `transaction-held`, `transaction-rejected` and `transaction-returned`
  carrying `debit-credit-code` credit in `events/inbound.clj`, and
  `payment/processor` handles `admit-inbound-payment` on
  `topic-payments-command`.
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

A participant is the service that runs it and the component kind or
base inside it, as the system configuration names them, and a topic
shows its partitions and the key it is published under. An arrow into
FDB labelled `read` reads, one labelled `commit` lists what a
transaction writes, and a shaded box holds the reads and the commit of
one FDB transaction; a read outside a box is a transaction of its own.

```mermaid
stateDiagram-v2
    [*] --> admitted: admit-inbound-payment<br/>opened account, checks pass
    admitted --> settled: transaction-settled (credit)<br/>no checks
    [*] --> settled: transaction-settled (credit)<br/>opened account, checks pass
    [*] --> suspended: transaction-settled (credit)<br/>account not opened, or refused<br/>park in 2500
    [*] --> held: transaction-held (credit)<br/>opened account, no money moves
    held --> settled: transaction-settled (credit)<br/>release, checks pass
    held --> suspended: transaction-settled (credit)<br/>release refused, park in 2500
    held --> returned: transaction-rejected (credit)<br/>back to the remitter
    suspended --> returned: transaction-returned (credit)<br/>2500 to 1100
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

```mermaid
sequenceDiagram
    participant PR as Modulr
    participant WH as external-adapters-service<br/>modulr-adapter webhook handlers
    participant DB as FDB
    participant OR as exclusive-dispatchers-service<br/>changelog-relay/runners
    participant SE as topic-schemes-payments-event<br/>2 partitions, key payment
    participant PE as financial-processors-service<br/>payment/event-processor
    PR-->>WH: PAYIN webhook
    rect rgb(235, 242, 255)
    Note right of WH: one FDB transaction
    WH->>DB: commit ModulrOutboxEvent transaction-settled (credit) and its changelog entry<br/>deduplicated on the provider's payment id
    end
    rect rgb(235, 242, 255)
    Note right of OR: one FDB transaction
    OR->>DB: read modulr-outbox from its cursor
    OR->>SE: transaction-settled (credit)
    OR->>DB: commit the cursor modulr-relay
    end
    SE->>PE: transaction-settled (credit)
    rect rgb(235, 242, 255)
    Note right of PE: one FDB transaction
    PE->>DB: read the account by BBAN, 1100, policies<br/>today's count at snapshot
    PE->>DB: commit Transaction inbound-transfer and two TransactionLegs<br/>the creditor's default/posted balance row<br/>InboundPayment settled and its inbound-payments changelog entry<br/>transaction-posted on bank-activity-shard<br/>the 1100 leg left out of the balance writes
    end
```

The settlement resolves the creditor by BBAN, checks the account is
opened and that the inbound capability and daily count allow it, and
posts DEBIT 1100 / CREDIT creditor. 1100 holds no row, its balance
being the sum of its legs, per
[ADR-0039](../adr/0039-cash-at-correspondents-balance-is-the-sum-of-its-legs.md),
so the only balance row written is the creditor's, and the current
account control it rolls into is summed from it. The transaction names
the creditor as the account the scheme moved the money through, so its
`transaction-posted` entry nets to nothing at a provider holding a
balance per account, which credited it itself. The `payments-event`
relay turns the changelog entry into `payment.inbound-settled`.

### Parked in suspense

```mermaid
sequenceDiagram
    participant SE as topic-schemes-payments-event<br/>2 partitions, key payment
    participant PE as financial-processors-service<br/>payment/event-processor
    participant DB as FDB
    participant AR as exclusive-dispatchers-service<br/>bank-activity/relay
    participant AE as topic-bank-activity-event<br/>1 partition, key bank
    participant AP as financial-processors-service<br/>payment/activity-event-processor
    SE->>PE: transaction-settled (credit)
    rect rgb(235, 242, 255)
    Note right of PE: one FDB transaction
    PE->>DB: read the account by BBAN, policies<br/>the account is not opened, or a check refuses
    PE->>DB: commit Transaction inbound-transfer, DEBIT 1100 and CREDIT 2500<br/>the 2500 suspense default/posted balance row<br/>InboundPayment suspended with the reason, and its changelog entry<br/>transaction-posted and inbound-payment-suspended on bank-activity-shard<br/>the 1100 leg left out of the balance writes
    end
    rect rgb(235, 242, 255)
    Note right of AR: one FDB transaction
    AR->>DB: read the bank's activity log shard from its cursor
    AR->>AE: transaction-posted and inbound-payment-suspended
    AR->>DB: commit the shard's cursor
    end
    AE->>AP: transaction-posted, mirrored at a per-account provider
    AE->>AP: inbound-payment-suspended, returned where the provider allows
```

An inbound the receiving account cannot take, because it is not opened
or a policy refuses it, is kept in the bank's 2500 suspense rather than
lost, recording the ISO 20022 reason as `suspense-reason-code`. 2500
keeps a stored row, which only these rare postings write. Under
`balances: per-account` the `transaction-posted` entry moves the money
from the receiving account's provider account to the bank's own funds,
as [payments-internal.md](payments-internal.md) describes. Under
`returns: [inbound]` the `inbound-payment-suspended` entry sends it
back, as below; otherwise it stays suspended for the bank to resolve.

### Held, then released or returned

```mermaid
sequenceDiagram
    participant PR as ClearBank
    participant WH as external-adapters-service<br/>clearbank-adapter webhook handlers
    participant DB as FDB
    participant OR as exclusive-dispatchers-service<br/>changelog-relay/runners
    participant SE as topic-schemes-payments-event<br/>2 partitions, key payment
    participant PE as financial-processors-service<br/>payment/event-processor
    PR-->>WH: the inbound held for screening
    rect rgb(235, 242, 255)
    Note right of WH: one FDB transaction
    WH->>DB: commit ClearbankOutboxEvent transaction-held (credit)
    end
    rect rgb(235, 242, 255)
    Note right of OR: one FDB transaction
    OR->>DB: read clearbank-outbox from its cursor
    OR->>SE: transaction-held (credit)
    OR->>DB: commit the cursor clearbank-relay
    end
    SE->>PE: transaction-held (credit)
    rect rgb(235, 242, 255)
    Note right of PE: one FDB transaction
    PE->>DB: read the account by BBAN
    PE->>DB: commit InboundPayment held and its changelog entry<br/>no transaction, no balance row
    end
    alt released
        PR-->>WH: the inbound settled
        rect rgb(235, 242, 255)
        Note right of WH: one FDB transaction
        WH->>DB: commit ClearbankOutboxEvent transaction-settled (credit)
        end
        rect rgb(235, 242, 255)
        Note right of OR: one FDB transaction
        OR->>DB: read clearbank-outbox from its cursor
        OR->>SE: transaction-settled (credit)
        OR->>DB: commit the cursor clearbank-relay
        end
        SE->>PE: transaction-settled (credit)
        rect rgb(235, 242, 255)
        Note right of PE: one FDB transaction
        PE->>DB: read the held InboundPayment, the account, policies<br/>today's count without the held record
        PE->>DB: commit Transaction and two TransactionLegs<br/>the creditor's default/posted balance row<br/>InboundPayment settled and its changelog entry<br/>transaction-posted on bank-activity-shard<br/>the 1100 leg left out of the balance writes<br/>or, where a check refuses, suspense as above
        end
    else returned to the remitter
        PR-->>WH: the inbound declined
        rect rgb(235, 242, 255)
        Note right of WH: one FDB transaction
        WH->>DB: commit ClearbankOutboxEvent transaction-rejected (credit)
        end
        rect rgb(235, 242, 255)
        Note right of OR: one FDB transaction
        OR->>DB: read clearbank-outbox from its cursor
        OR->>SE: transaction-rejected (credit)
        OR->>DB: commit the cursor clearbank-relay
        end
        SE->>PE: transaction-rejected (credit)
        rect rgb(235, 242, 255)
        Note right of PE: one FDB transaction
        PE->>DB: read the held InboundPayment
        PE->>DB: commit InboundPayment returned and its changelog entry<br/>no transaction, the money never reached the bank
        end
    end
```

A provider that screens holds an inbound before it settles, ClearBank
here and Modulr through its compliance notification. The platform
records the hold with no money moving, and the settlement or rejection
that follows finds it by end-to-end id, creditor and amount. A hold for
an account that is not opened is ignored, so its settlement parks in
suspense.

### Admitted before it settles

```mermaid
sequenceDiagram
    participant PR as Form3
    participant WH as external-adapters-service<br/>form3-adapter webhook handlers
    participant PC as topic-payments-command<br/>2 partitions, unkeyed
    participant PP as financial-processors-service<br/>payment/processor
    participant DB as FDB
    participant OR as exclusive-dispatchers-service<br/>changelog-relay/runners
    participant SE as topic-schemes-payments-event<br/>2 partitions, key payment
    participant PE as financial-processors-service<br/>payment/event-processor
    PR-->>WH: an inbound asking for admission
    WH->>PC: admit-inbound-payment, waiting for the reply
    PC->>PP: admit-inbound-payment
    rect rgb(235, 242, 255)
    Note right of PP: one FDB transaction
    PP->>DB: read the account by BBAN, policies<br/>today's count at snapshot
    alt admitted
        PP->>DB: commit InboundPayment admitted and its changelog entry<br/>no transaction, no balance row
    else rejected
        Note right of PP: nothing committed, the reply names the ISO 20022 reason
    end
    end
    PP-->>WH: reply on topic-payments-command-response
    WH-->>PR: admitted, or rejected with the reason
    PR-->>WH: the inbound settled
    rect rgb(235, 242, 255)
    Note right of WH: one FDB transaction
    WH->>DB: commit Form3OutboxEvent transaction-settled (credit)
    end
    rect rgb(235, 242, 255)
    Note right of OR: one FDB transaction
    OR->>DB: read form3-outbox from its cursor
    OR->>SE: transaction-settled (credit)
    OR->>DB: commit the cursor form3-relay
    end
    SE->>PE: transaction-settled (credit)
    rect rgb(235, 242, 255)
    Note right of PE: one FDB transaction
    PE->>DB: read the admitted InboundPayment, the account, 1100
    PE->>DB: commit Transaction and two TransactionLegs<br/>the creditor's default/posted balance row<br/>InboundPayment settled and its changelog entry<br/>transaction-posted on bank-activity-shard<br/>the 1100 leg left out of the balance writes<br/>no checks run again
    end
```

Under `inbound: admitted` the provider asks before an inbound settles,
and the adapter waits for `payment`'s answer until the provider's
deadline, rejecting with `NARR` where none came. `payment` admits where
the BBAN names an opened account and the checks a settlement runs pass,
and rejects otherwise: `AC01` for a BBAN matching no account, `AC04` for
one closed, `AC06` for one not opened, `AG01` for a payment a policy
refuses. The business-day counts include admitted payments, so two
admitted at once cannot pass one limit between them. An admission
redelivered for a payment already recorded answers admitted and records
nothing. A settlement for an admitted payment whose account has since
stopped being opened parks it in suspense.

### Returned from suspense

```mermaid
sequenceDiagram
    participant AP as financial-processors-service<br/>payment/activity-event-processor
    participant FC as topic-form3-command<br/>1 partition, key bank
    participant AD as external-adapters-service<br/>form3-adapter/command-processor
    participant IP as external-adapters-service<br/>form3-relay/outbound-runner
    participant PR as Form3
    participant DB as FDB
    participant OR as exclusive-dispatchers-service<br/>changelog-relay/runners
    participant SE as topic-schemes-payments-event<br/>2 partitions, key payment
    participant PE as financial-processors-service<br/>payment/event-processor
    AP->>FC: return-payment, from inbound-payment-suspended
    FC->>AD: one command at a time
    rect rgb(235, 242, 255)
    Note right of AD: one FDB transaction
    AD->>DB: commit Form3OutboundIntent, the return, pending
    end
    IP->>DB: read pending and sent intents
    IP->>PR: the return
    rect rgb(235, 242, 255)
    Note right of IP: one FDB transaction
    IP->>DB: commit the intent sent
    end
    IP->>PR: reconciled, what became of it
    rect rgb(235, 242, 255)
    Note right of IP: one FDB transaction
    IP->>DB: commit the intent's outcome<br/>Form3OutboxEvent transaction-returned (credit), or inbound-return-failed
    end
    rect rgb(235, 242, 255)
    Note right of OR: one FDB transaction
    OR->>DB: read form3-outbox from its cursor
    OR->>SE: transaction-returned (credit)
    OR->>DB: commit the cursor form3-relay
    end
    SE->>PE: transaction-returned (credit)
    rect rgb(235, 242, 255)
    Note right of PE: one FDB transaction
    PE->>DB: read the suspended InboundPayment, 2500, 1100
    PE->>DB: commit Transaction inbound-return, DEBIT 2500 and CREDIT 1100<br/>two TransactionLegs, the 2500 suspense balance row<br/>InboundPayment returned and its changelog entry<br/>transaction-posted on bank-activity-shard<br/>the 1100 leg left out of the balance writes
    end
```

Under `returns: [inbound]` the activity event processor sends a
suspended inbound back with its reason. The adapter learns the return
was delivered when it next reconciles, and reports
`transaction-returned` (credit), deduplicated on the provider's id for
the inbound, which empties suspense of the payment. A return the
provider refuses or does not deliver is reported as
`inbound-return-failed`, and the payment stays suspended carrying it as
`return-failure-reason`. A second report is a no-op, and one for a
payment not suspended fails the handler.

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
- **An admission not answered in time is rejected.** The payment is
  refused with `NARR` and the sender's bank tells its customer.
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
- [ADR-0039](../adr/0039-cash-at-correspondents-balance-is-the-sum-of-its-legs.md)
  — 1100 summed from its legs.
- [ISO 20022 external code sets](https://www.iso20022.org/catalogue-messages/additional-content-messages/external-code-sets)
  — the reason codes an admission rejects with.
