# Chart of accounts

## Objective

A bank keeps its own books. Customer cash-accounts are *what
the bank sells*; they are not *the bank's books*. The bank's
books are a chart of accounts — a structured set of
general-ledger (GL) accounts grouped Asset / Liability /
Equity / Income / Expense — against which every customer
movement, every fee, every interest accrual, every
settlement-clearing event posts as double-entry journals.

Queenswood models the bank's books as a first-class **chart of
accounts** — a per-bank artefact. The `ledger-account`
brick owns the GL account entity (its own FDB record type) and
the canonical seeded template; the **sub-ledger / GL split**
keeps customer cash-accounts as they are while routing their
financial-statement effect to per-product-type control
accounts; and two invariants — the trial balance ties, and
each control holds its sub-ledger's roll-up — are forcing
functions the scenario runners prove after every step. Fees and
interest get their symmetric counter-legs, closing the gap
transactions-and-balances calls out (*"a fee today is one debit
leg with no matching credit"*).

In scope: the `ledger-account` brick; the GL account data
model; A/L/E/I/E grouping; per-product-type control accounts
that aggregate the cash-accounts of each product type (customer
deposits and the bank's own funds); the rule that GL leg-sets
must balance per currency; the canonical seeded chart and its
codes; the product-type → control mapping a control's balance
is summed by; the per-currency trial balance the
chart's list route returns; the two scenario-testing invariants
that prove correctness on every run.

Out of scope: the balance sheet and the P&L — a future PRD/TDD
will consume the GL; financial-period closing (the closing-out
journal that flips accumulated income/expense into retained
earnings); multi-segment / cost-centre GL; export to external
accounting systems; Queenswood-the-platform's own books (SaaS
subscription / usage revenue) — a separate platform-level
concern not exposed in tenant APIs.

## Background

The bank-in-a-box positioning makes the tenant itself a bank.
That tenant needs to see and reconcile **its own** books, not
just its customers' balances. Real core-banking systems are
built around two distinct artefacts:

- A **sub-ledger** — per-customer detail at instrument
  granularity (every customer cash-account, every loan, every
  card). The customer-facing balance lives here.
- A **general ledger** — the bank's own books, organised by
  the chart of accounts (A/L/E/I/E), recording every financial
  effect of every sub-ledger movement and every bank-only
  event (P&L, equity).

The sub-ledger answers *"what does this customer have?"*; the
GL answers *"what does the bank own and owe, and what did it
earn and spend?"*. Both are needed; neither subsumes the
other.

Queenswood realises this split:

- Customer cash-accounts are the **sub-ledger** — per-customer
  detail at instrument granularity. Each carries
  `:balance-sheet-side` and rolls up to a per-product-type
  control account in the GL.
- The **general ledger** is a set of `LedgerAccount` records
  the bank owns, grouped A/L/E/I/E. It records every financial
  effect: the customer-deposit controls, the bank's cash at
  correspondent, interest payable, the bank's own funds, and
  suspense.
- A control account's balance is the sum of the posted default
  balances of the cash accounts that roll into it, so a customer
  posting moves the bank's books without a leg of its own on the
  control. Fees and interest add their own GL counter-legs so the
  bank's P&L is modelled symmetrically.

## Proposed Solution

### Architecture

GL accounts are their **own record type**, `LedgerAccount`,
owned by the `ledger-account` brick. A `LedgerAccount` is
a flat, bank-owned record — 1:1 with a chart row, created
directly at bank-provisioning time, with no product, no
versioning, and no command or event lifecycle. It is distinct
from a customer `CashAccount`: the GL is the bank's own books,
not something the bank sells.

Cash-accounts are the **sub-ledgers**. Two kinds exist, both
ordinary `CashAccount` records under a `CashAccount(Product)`:

- **Customer cash-accounts** — the instruments the bank sells
  (current / savings / term-deposit), one per customer.
- **The bank's own-funds account** — one per bank per
  currency, held on the bank's own org party. The bank
  pre-funds it and pays customers from inside the bank (see
  "The bank's own funds" below).

Each cash-account rolls up to a GL **control account** via its
`:product-type`. `ledger-account` owns:

- the `new-account` call that creates one `LedgerAccount` plus
  its opening balance (callers loop it over a supplied chart);
- `product-type->control-code` — the product-type → control
  `:gl-account-code` role mapping;
- `find-by-code` — resolve a GL account by its role and
  currency;
- `get-balances` — a ledger account's balances, a control's summed
  from its sub-ledger;
- `ensure-controls` — refuse a posting whose posted default
  sub-ledger leg rolls into a control that is missing or closed,
  keyed off the leg's `:product-type`.

The canonical chart **template lives in a `bank`
resource**, and `bank` provisions it: `new-bank` seeds one
`LedgerAccount` per template row per currency and opens the
own-funds cash account.

```mermaid
graph LR
    L["LedgerAccount<br/>(GL: controls + detail)<br/>ledger-account"]
    A["CashAccount<br/>(customer + own-funds)<br/>cash-account"]
    BANK["new-bank: seed chart<br/>+ own-funds account<br/>bank"]
    EL["ensure-controls<br/>(product-type → control)<br/>ledger-account"]
    TX["Legs + Balances<br/>transaction<br/>balance"]
    FDB[("FDB<br/>one transaction")]

    BANK -->|seeds| L
    BANK -->|opens| A
    A -->|legs| TX
    EL -->|checks the control is open| TX
    TX -->|postings| FDB
    L -->|control balance summed from| FDB
```

`transaction` and `balance` treat cash-accounts and
GL accounts uniformly: the account-id space is **shared** — a
`led.` ledger-account-id is just another `account-id`, exactly
like a customer's `acc.` id. The balance-bucket model
`(account-id, balance-type, balance-status, currency)` carries
GL bucket totals exactly as it carries customer bucket totals,
and the bricks don't distinguish. A control is the exception: it
has no bucket of its own, and its balance is read from the legs'
SUM index over its sub-ledger — see "Control balances" below.

The CoA is **per-bank**. Each tenant defines its own structure
within the fixed A/L/E/I/E top-level grouping. Banks on the
same platform never share a CoA — each one is its own books.

### Five classes and the code convention

Five top-level classes, numbered by convention:

| Range | Class     | Normal side |
|-------|-----------|-------------|
| 1xxx  | Asset     | Debit       |
| 2xxx  | Liability | Credit      |
| 3xxx  | Equity    | Credit      |
| 4xxx  | Income    | Credit      |
| 5xxx  | Expense   | Debit       |

The numbering is **convention, not enforcement** for reporting
and trial-balance ordering. The well-known accounts code
actually relies on, though, carry a typed **role** rather than a
bare number: each is a value of the `GlAccountCode` enum, and the
enum's integer value *is* the chart number
(`GL_ACCOUNT_CODE_SUSPENSE = 2500`). Posting sites resolve an
account by role (`:gl-account-code-suspense`), never by the
literal string — so renumbering the chart can't silently break
posting logic. The number is reconstituted as a string only at
the API/reporting edge (`gl-account-code->gl-code`).

### Data model

A GL account is a `LedgerAccount` record — flat, bank-owned,
one row of the chart:

```protobuf
message LedgerAccount {
  required string bank_id = 1;
  required string ledger_account_id = 2;   // led.<uuidv7>
  required GlAccountCode gl_account_code = 3;
                                       // role; enum value = chart number
  required string name = 4;
  required LedgerAccountStatus status = 5; // open or closed
  required string currency = 6;            // ISO 4217
  required GlAccountType gl_account_type = 7;
                                       // detail, control or summary

  required int64 created_at = 101;
  required int64 updated_at = 103;
}
```

Indexed primary key `(bank_id, ledger_account_id)`, with a
`LedgerAccount_by_bank_gl_account_code` index so `find-by-code`
resolves a GL account by its role.

A cash-account (customer or own-funds) is an ordinary
`CashAccount` record; the only field that matters for the GL is
its denormalised `product_type`, which names the control its
balances are summed into:

```protobuf
message CashAccount {
  required string bank_id = 1;
  required string account_id = 2;          // "acc.<uuidv7>"
  required string party_id = 4;
  required string product_id = 5;
  required string version_id = 6;
  required string currency = 9;
  required string name = 8;
  required CashAccountStatus account_status = 10;
  optional ProductType product_type = 7;   // drives the control mapping
  optional AccountType account_type = 3;    // personal, business
  repeated PaymentAddress payment_addresses = 11;
  optional string bban = 12;
  required int64 created_at = 13;
  required int64 updated_at = 14;
}
```

Notes:

- **GL accounts and cash accounts are separate record types.**
  A `LedgerAccount` carries its chart role (`gl_account_code`)
  and how it posts (`gl_account_type`) directly on the record;
  there is no product behind it. A `CashAccount` carries no GL fields — its
  control is derived from `product_type`.
- **The control link is *not* stored on the cash account.**
  There is no `gl_control_account_id`. A control's balance is
  summed from the balance buckets tagged with a product type
  that `product-type->control-code` maps to it. Keying the
  roll-up off the product type — not a stored pointer — means
  re-coding the chart needs no per-account migration.
- **`product_type`** stays denormalised on cash accounts for
  the existing
  `CashAccount_count_by_bank_product_type_account_type_currency`
  index, and now distinguishes customer instruments
  (`-current` / `-savings` / `-term-deposit`) from the bank's
  own-funds account (`-own-funds`).
- **`account_type`** (personal / business) is derived from the
  holder party. Customer accounts on a person party are
  personal; the own-funds account on the bank's org party is
  business.
- **The class is derived from the code.** An account's class —
  asset, liability, equity, income or expense — is the thousand
  of its chart number, which `ledger-account/gl-account-class`
  reads, so no stored class can disagree with the code. The API
  still names it `gl-account-type`, and the type
  `gl-account-class`, until its names are revisited.
- **`gl_account_type`** distinguishes three:
  - `detail` — leaf, accepts legs.
  - `summary` — rolls up children, never receives legs
    directly.
  - `control` — special leaf that aggregates a sub-ledger.
    Detail lives elsewhere (in customer cash-accounts); the
    control account is the GL's single line item for that
    sub-ledger cohort.
- **The sub-ledger a control stands for** is not stored: the
  roll-up is driven by `product-type->control-code`, which maps
  a `:product-type` to the control `:gl-account-code` directly.
- **Normal side** is *derived*, not stored, from the class —
  assets and expenses are debit-normal; liabilities, equity,
  and income are credit-normal. Reporting derives at read
  time.
- **`currency`** lives on the `LedgerAccount` record, one
  currency per account — see "Currency" below for the flat
  per-currency chart that follows from it.

### The seeded standard chart

Every bank starts with a minimal seeded CoA — enough to
support the existing payment and interest flows without manual
setup. The bank can extend it freely; the seeded accounts
cannot be deleted (status flip only).

| Code | Name                              | Type      | Class   |
|------|-----------------------------------|-----------|---------|
| 1100 | Cash at correspondent             | Asset     | Detail  |
| 1200 | Pending outbound payments         | Asset     | Detail  |
| 2100 | Customer deposits — current       | Liability | Control |
| 2200 | Customer deposits — savings       | Liability | Control |
| 2300 | Customer deposits — term deposits | Liability | Control |
| 2400 | Interest payable                  | Liability | Control |
| 2500 | Suspense — unreconciled inbound   | Liability | Detail  |
| 3100 | Bank own funds                    | Equity    | Control |
| 5100 | Interest expense                  | Expense   | Detail  |

Normal side follows from type per the convention table above
(A and E are debit-normal; L, Eq, I are credit-normal).

The five control accounts each aggregate a cohort of
sub-ledger balances: 2100 / 2200 / 2300 hold the customer
current / savings / term-deposit deposits, **2400 aggregates
the customer interest-accrued balances** (interest the bank
owes but has not yet capitalised), and **3100 holds the
bank's own funds** (the own-funds cash account the bank funds
and pays customers from). A deposit or own-funds control's
balance is the sum of the cash accounts of its product type, and
2400's the sum of their interest-accrued buckets — see "Balance
buckets per account class" below.

Accounts the chart will grow when those flows land — fee
income (4xxx), retained earnings, accrued fees receivable —
are not seeded today; a bank adds them as it needs them.

`1100 — Cash at correspondent` is the bank's own settlement
account at its clearing rail. For a directly-connected bank it
is the bank's account at the central-bank RTGS or scheme
operator; for an indirect-access bank it is the bank's view of
its position with its sponsor, the same position the sponsor's
own books carry as a `CPAC` clearing-participant account. The
ISO 20022 classification of an account is a product field rather
than a chart one — see
[cash-account-products.md](cash-account-products.md).

A bank participating in more than one scheme adds per-scheme
children — 1101 FPS, 1102 CHAPS, and so on — each typed as a
`default/posted` asset detail. The seed creates a single 1100 by
default, sufficient for an FPS-only deployment.

The seeded set is small on purpose. A bank that wants finer
breakdown (per-currency cash-at-correspondent children,
per-business-line expense buckets, multi-tier savings-product
sub-controls) extends the CoA itself.

### Sub-ledger / GL split

Cash-accounts are the **sub-ledger** for the matching control
account. The link is the account's `:product-type`, mapped to
a control `:gl-account-code` by `product-type->control-code`:

| Product type           | Control code | Cohort            |
|------------------------|--------------|-------------------|
| current                | 2100         | customer deposits |
| savings                | 2200         | customer deposits |
| term-deposit           | 2300         | customer deposits |
| own-funds              | 3100         | the bank's funds  |

The mapping is a property of the bank's CoA (held by
`ledger-account`'s `product-type->control-code`), not
a constant. A control's balance is summed from the buckets of its
product types, and a posting resolves the control by code to check
it is open — there is no per-account stored pointer, so re-coding
the chart needs no per-account migration.

### Balance buckets per account class

The bucket model `(balance-type, balance-status, currency)`
applies to every account; what differs by account class is
*which* buckets are maintained. A bucket is opened by the first
leg that names its `(balance-type, balance-status)` pair — the
`balance` brick's `new-zero-balance` creates it and then applies
the leg — so what an account carries is what its postings have
reached, not a fixed allocation.

**Customer cash-accounts** carry a four-bucket layout:

| Balance type        | Statuses                                         |
|---------------------|--------------------------------------------------|
| `default`           | `posted`, `pending-incoming`, `pending-outgoing` |
| `interest-accrued`  | `posted`                                         |

`available-balance` derives the customer-visible spendable
amount by summing buckets per product type — see
[transactions-and-balances.md](transactions-and-balances.md).
The bank's **own-funds cash account** carries a single
`default / posted` bucket — it doesn't earn interest and has no
pending lifecycle; its available balance is just its posted
default.

**Every GL account but the deposit and own-funds controls, 1100
and 1200** carries a single stored bucket, and 1100 a single summed
one:

| Balance type | Statuses |
|--------------|----------|
| `default`    | `posted` |

The deposit and own-funds controls (2100 / 2200 / 2300 / 3100)
carry none. Each one's `default / posted` balance is the sum of
the `default / posted` buckets of the cash accounts whose product
type rolls into it, read from a SUM index on the legs store over
`amount`, grouped by bank, product type, currency, bucket and side,
which the Record Layer keeps by atomic mutation as each leg is
saved; a cash account's own buckets are the sums of its legs too,
per [ADR-0042](../adr/0042-a-cash-accounts-balance-is-the-sum-of-its-legs.md).
A posting
writes only the accounts its legs name, so two payments in one
bank share no row on the control's account, a read-modify-write
no account key could otherwise spread; see
[ADR-0037](../adr/0037-a-control-accounts-balance-is-the-sum-of-the-balances-that-roll-into-it.md).
A customer bucket in `pending-incoming` or `pending-outgoing` is
not summed, so the control moves when the payment posts. A
customer `interest-accrued` bucket is summed into 2400 rather than
the deposit control, so interest is counted once, on 2400, until
capitalisation moves it to the default bucket and so to 2100; see
[interest.md](interest.md). 2500 is a detail account, posted to
directly. 1100 and 5100 are detail accounts whose balance is the
sum of their legs, read from a SUM index on `transaction-legs` over
`amount` grouped by account, bucket and side: every posting naming
one still records its leg, but the leg is left out of the balance
writes, so settlements in one bank share no row on 1100, and an
interest run's chunks none on 5100; see
[ADR-0039](../adr/0039-cash-at-correspondents-balance-is-the-sum-of-its-legs.md)
and
[ADR-0042](../adr/0042-a-cash-accounts-balance-is-the-sum-of-its-legs.md).

A bucket a leg opens takes the leg's `:product-type`, else that of
the account's existing buckets, and is tagged
`:product-type-general-ledger` only on an account with neither,
so a cash account's buckets are always summed into its own
control.

**1200 Pending outbound payments** carries none either. Its
`default / pending-outgoing` balance mirrors the `default /
pending-outgoing` buckets of every cash account whose product type
rolls into a control, read from the same SUM index with credit
and debit swapped. The outbound reservation still carries a leg
crediting 1200 beside the customer's debit, and the settlement or
a reversal one debiting it, so every transaction balances, but
`stored-legs` drops those legs before the balances are written, so
outbound payments in one bank share no row on 1200; see
[ADR-0038](../adr/0038-an-outbound-submit-writes-no-row-every-payment-shares.md)
and [payments.md](payments.md).

The account's *identity* carries what `balance-type` encodes
on customer and control accounts. Pending semantics are
expressed by separate GL accounts where business value exists
(1200 *Pending outbound payments* is its own asset account,
distinct from 1100 *Cash at correspondent*) rather than by a
pending status on a single account.

The bank's liability to customers for interest is GL 2400's
`default / posted` balance, the sum of the customers'
`interest-accrued / posted` buckets — there's no separate
`interest-payable` typed bucket on another account.

### The two invariants

Two invariants hold over the bank's books, and both scenario
runners assert both after every step. They are
`clojure.test/is` assertions inside the runner, not `nom-test>`
assertions — `nom-test>` says a value is not an anomaly, which
is a different claim from the books tying.

**Invariant 1 — the trial balance ties.** Per currency, across
the whole chart:

```
Σ debit = Σ credit
```

**Invariant 2 — sub-ledger ↔ control.** For each deposit or
own-funds control account (2100 / 2200 / 2300 / 3100), per
currency, at `:balance-status-posted` alone:

```
control's default / posted balance
  =
Σ default / posted balance
  across every cash-account whose :product-type
  maps to this control
```

The second is restricted to posted because the sum is: an
in-flight bucket rolls into no control. A control's side is read
from the legs' SUM index, grouped by the product type each leg was
stamped with, and the sub-ledger's by scanning cash accounts,
grouped by each account's, so the reconciliation catches a leg
filed under a product type other than its account's.

Neither invariant subsumes the other. A posting that debits one currency's
1100 and credits the same currency's 3100 ties even when it was
meant for another currency's rows; only the reconciliation,
which compares each control against its own sub-ledger, catches
the mis-routing. A posting whose offset never reached the GL at
all breaks the tie while leaving every control reconciled.

`test-scenarios/invariants.clj` reads both sides out of a single
`fdb/transact` snapshot, so a settlement committing mid-read
cannot tear them apart. `test-api-scenarios/invariants.clj`
reads the same two facts off the wire, for every bank the run
holds a token for: the per-currency `:trial-balance` block and
each control's `:posted-balance` from `GET /v1/ledger-accounts`,
the sub-ledger from a cursor-paged walk of
`GET /v1/cash-accounts?embed=balances`. That runner holds no FDB
config, and a bank whose token cannot be minted is logged and
listed under its `:skipped-banks` rather than left silently
unasserted.

A balance read that fails is not treated as zero. An anomaly
from any of these reads fails an assertion naming the bank and
the account, rather than skipping it — an invariant that holds
because nothing was read is the failure this design exists to
rule out.

A third reconciliation holds for interest. It is a
scenario-level assertion rather than a standing one, carried by
the `:assert-interest-reconciliation` verb in the
`interest-accrual` scenario, after the accrual run and again
after capitalisation:

```
2400 balance per currency
  =
Σ interest-accrued / posted balance per currency
  across every customer cash-account
```

2400 is summed from the same buckets by product type, through the
legs' index, and the other side account by account, so the
reconciliation checks that every accrued leg carries its account's
product type.

The first invariant is enforced *in the commit path* —
`validate-legs` rejects an unbalanced posting before commit. The
second is enforced *by construction* — a control's balance is
its sub-ledger's sum — and verified after every step of every
scenario. Any change that breaks reconciliation fails
every scenario, not just the one that introduced it. See
[scenario-testing.md](scenario-testing.md) for the two runners.

### Double-entry enforcement

Every `record-transaction` is checked, whether or not a GL
account is among its legs. `transaction`'s `validate-legs` runs
two rules over the leg-set:

- Every leg's amount must be positive. A zero or negative amount
  rejects `:transaction/invalid-amount`.
- The legs must balance, Σ debit against Σ credit. An imbalance
  rejects `:transaction/legs-unbalanced`.

There is no separate rule for a posting that touches the GL, and
no GL-specific rejection kind. `transaction` and `balance` do
not tell a `led.` account-id from an `acc.` one. A customer leg
stands for its control in the balance, since the control is its
sub-ledger's sum.
[transactions-and-balances.md](transactions-and-balances.md)
describes the same rules from the leg substrate's side.

Worked example — £100 inbound deposit to a current account:

```
DEBIT  1100 Cash at correspondent   default / posted  100 GBP
CREDIT customer-acc                 default / posted  100 GBP

;; debit 100 = credit 100 ✓
;; 2100 Customer deposits — current rises by 100, summed from
;; the customer's bucket
```

### Control balances

A control's balance is the live roll-up of its sub-ledger's posted
default buckets, and nothing else: the sum of the `default / posted`
buckets of every cash account whose `:product-type` maps to it, in
its currency. `ledger-account`'s `get-balances` returns it as the
control's one `default / posted` balance, read from the legs'
SUM index at snapshot, so the read neither conflicts with
nor holds up the postings that move it. The list route, the balances
route and the scenario invariants read it the same way; the
`:gl/non-zero-on-close` guard reads it serializably, inside the
transaction that closes the account.

A cash account and its control represent the same obligation at
different granularities, so they move in lockstep without a leg
between them. The opposite leg of a posting lives on a *different*
account — typically `1100 Cash at correspondent` for an external
movement, or another cash account for an internal one. An internal
transfer between two current-account customers moves 2100 by
nothing at all, since one customer's debit and the other's credit
sum into it together.

One movement deliberately does not reach a control:

- **The outbound reservation.** Submitting an outbound payment
  writes the customer's `default / pending-outgoing` bucket, which
  1200 mirrors and no deposit control sums. The deposit control
  moves at settlement, when the customer's posted debit is recorded
  — see [payments.md](payments.md).
A posting site calls `ensure-controls` on its legs before recording
them. For each posted default leg carrying a sub-ledger
`:product-type`, it resolves the control that product type maps to,
and for each posted interest-accrued one 2400, reading the
`LedgerAccount` record, and returns the legs unchanged.
Two rejections originate there, and both fail the whole posting:

- `:gl/missing-currency-account` — the bank's chart has no row for
  that control role in that currency.
- `:ledger-account/closed` — the control resolves and has been
  closed.

The `interest` brick raises its own
`:interest/missing-gl-account` when a run cannot resolve 5100 or
2400 for its currency. The two differ by who was posting: one is
a posting whose control was missing, the other an interest run
that found no chart to post against.

Interest calls `ensure-controls` on each transaction a chunk
records: accrual's credits each account's interest-accrued bucket
and debits 5100, and capitalisation's moves each account's interest
from that bucket to its default one, both legs carrying the
account's product type.

### The bank's own funds

A bank holds its own money, distinct from customer deposits.
That money lives in the bank's **own-funds cash account** — an
ordinary `CashAccount` on the bank's org party, opened at
provisioning under the `own-funds` product, rolling up into the
**3100 Bank own funds** equity control. It is BBAN-addressable
and transactable like any cash account; nothing about it is
special except the control it points at.

The bank is funded from outside, and pays customers from
inside:

- **Money in.** An external inbound lands in `1100 Cash at
  correspondent` (asset up) and credits the own-funds account:

  ```
  DEBIT  1100 Cash at correspondent      default / posted  amount
  CREDIT own-funds cash account          default / posted  amount
  ;; legs balance ✓
  ;; 3100 Bank own funds rises, summed from the own-funds account
  ```

- **Paying a customer from inside.** A reward, a goodwill
  credit — anything the bank funds itself — is an internal
  transfer from the own-funds account to the customer, no
  external payment however many customers:

  ```
  DEBIT  own-funds cash account          default / posted  amount
  CREDIT customer cash account           default / posted  amount
  ;; legs balance ✓
  ;; 3100 falls and 21x0 rises, each summed from its sub-ledger
  ```

Keeping the bank's money in its own GL line (3100 equity)
rather than lumped into a customer-deposit control keeps the
books honest: `1100` (the bank's cash) backs `2100 / 2200 /
2300` (customer deposits) *and* `3100` (the bank's own funds),
and the trial balance reads true.

A customer funding their own account from another bank works
the same way: the external inbound lands in 1100 and credits
the customer's account (and so its 2100 / 2200 / 2300
control). Suspense (2500) is reserved for inbounds that can't
be matched to an account.

### The trial balance on the list route

`GET /v1/ledger-accounts` returns the bank's chart with two
figures derived server-side.

Each account carries a `:posted-balance` — the same
`{:value :currency}` figure the per-account balances route
returns, read per account and attached to the list, so a client
gets the headline number without a request each.

The response then carries a per-currency `:trial-balance` block
of `{:currency :debit :credit :accounts}`, one entry per
currency, where `:accounts` counts the chart rows in it. Each
account's posted net is projected into a column by its normal
side: `debit-normal?` puts assets and expenses in the debit
column and liabilities, equity and income in the credit one, so
a currency whose books tie shows equal debit and credit.
Currencies never sum together, so there is no grand total.

`TrialBalanceEntry` is the API component for one entry, carried
on `LedgerAccountList` beside the accounts; the console's ledger
view renders the block.

The cost is a balance read per chart row on every list. That is
tolerable for a chart of nine rows per currency and is the
reason the reporting surfaces named under "Known Limitations"
would not be built this way.

### Lifecycle

A `LedgerAccount` has no command or event lifecycle, no draft /
published distinction, and no versioning. It is created at
bank-provisioning time by `new-account`, which `new-bank` loops
over the chart template, and the `/ledger-accounts` API exposes
list, get and balances only.

`LedgerAccountStatus` has two values, `open` and `closed`. An
unset status reads as open, which is what let the field be added
without backfilling the rows seeded before it. `close-account`
performs the one transition, `open -> closed`, under three
guards:

- `:ledger-account/invalid-status` — the account is not open.
- `:gl/non-zero-on-close` — its `default / posted` bucket does
  not net to zero.
- the `:ledger-account` close capability, which a tier may deny.

A closed account is not quietly skipped by a posting.
`find-by-code` and `ensure-controls` both reject
`:ledger-account/closed`, so a posting that needed the account,
or moved a closed control's sub-ledger, fails.

Close is in-process only: no route, no command, and no
production caller. `close-account` is on the brick's interface
and reachable from a test or from a REPL against a booted
system. A tenant-facing close is future work — see "Known
Limitations".

### Currency

Every `LedgerAccount` is single-currency, and the chart is flat:
one row per chart row per currency, nine per currency, no
summary parents and no parent/child hierarchy. A bank created in
three currencies has twenty-seven rows.

The loop runs inside `new-bank`'s `store/transact` in the `bank`
brick — every currency crossed with every template row, in the
transaction that creates the bank — so a failed create leaves no
chart behind.

A posting in currency X resolves the X-denominated row for its
role. `find-by-code` takes a currency and queries
`LedgerAccount_by_bank_gl_account_code`, whose fields are
`[bank_id, gl_account_code, currency]`, so the lookup is exact
rather than a scan whose winner depends on the order the chart
was seeded in. A bank with no row for that (role, currency) pair
rejects `:gl/missing-currency-account`.

A multi-currency GL position — "all of Interest payable" — is
the sum of the per-currency rows sharing a `:gl-account-code`
role, computed at read time. Nothing stores it, and no account
at any layer holds more than one currency.

### Bricks involved

- **`ledger-account`** owns the `LedgerAccount` record type and
  the GL surface: `new-account` (create one GL account plus, but
  for a control, its opening balance), `close-account`,
  `find-by-code`, `get-account`, `list-accounts`, `get-balances`
  (a control's summed from its sub-ledger),
  `product-type->control-code`, `gl-account-code->gl-code`,
  `debit-normal?`, and `ensure-controls` (refuse a posting whose
  control is missing or closed, keyed on a leg's
  `:product-type`).
- **`bank`** holds the canonical chart template in a
  resource; `new-bank` seeds one `LedgerAccount` per row per
  currency and opens the own-funds cash account on the bank's
  org party.
- **`cash-account` / `cash-account-product`** carry
  the customer and own-funds cash-accounts. The own-funds
  product (`:product-type-sub-ledger-own-funds`) maps to
  control 3100; customer products map to 2100 / 2200 / 2300.
- **`payment`** resolves GL accounts via
  `ledger-account/find-by-code`, tags its customer legs with
  `:product-type`, and checks their controls via
  `ensure-controls`.
- **`interest`** resolves 5100 and 2400 through its own
  `domain/chart.clj` before a run touches an account: a chunk's
  accrual debits 5100 and credits each account's interest-accrued
  bucket, which 2400 sums, and capitalisation moves it from there
  to the default bucket.
- **`transaction` / `balance`** record legs and
  maintain bucket balances uniformly across cash (`acc.`) and
  ledger (`led.`) account-ids; `validate-legs` checks every
  posting, and `new-zero-balance` opens a bucket on first use.
- **`schema`** defines the `LedgerAccount` message and the
  `GlAccountType` / `GlAccountCode` / `LedgerAccountStatus`
  enums, the `LedgerAccount` entry in `RecordTypeUnion`, and the
  `LedgerAccount_by_bank_gl_account_code` index. `ProductType`
  carries the sub-ledger values `-current` / `-savings` /
  `-term-deposit` / `-own-funds` plus `-general-ledger`.
- **`api`** exposes a read-only `/ledger-accounts` surface
  (list / get / balances), derives the list route's
  `:posted-balance` and `:trial-balance` block, and maps the GL
  and ledger-account rejection kinds to their statuses.
  Transaction-leg responses accept a cash-account *or* a
  ledger-account id (the shared id space).
- **`test-scenarios` / `test-api-scenarios`** each assert both
  standing invariants after every scenario step.

### Policy integration

The `:ledger-account` capability gates two actions.
`ledger-account-action-open` is checked by
`domain/new-ledger-account`, so a tier that denies it cannot
mint a GL account; `ledger-account-action-close` is checked by
`domain/close`. The seeded policies take opposite positions on
both: the platform tier allows them, and the micro tier denies
them.

A micro bank's chart is seeded all the same. `new-bank` resolves
the bootstrap policies once and passes them into `new-account`
as `:policies`, rather than letting the brick resolve the bank's
own effective policies — provisioning is the platform acting,
not the tenant. What the micro deny withholds is a later open or
close made under the bank's own policies.

Beyond those two actions there is no gate, because there is
nothing yet to gate: the `/ledger-accounts` surface is read-only
and no route closes an account. When CoA authoring lands —
adding, re-coding or reclassifying a chart row — a per-account
posting filter on `:balance` would join them, so a policy could
restrict who posts to a given account. Count limits per bank
fall out of the same `:aggregate :count` mechanism the product
brick uses.

### Caller contract

A caller that posts cash-account legs:

1. Builds the legs, each tagged with its account's
   `:product-type`.
2. Calls `ledger-account/ensure-controls` on them, inside the
   transaction it is about to record in.
3. Calls `transaction/record-transaction` with the legs, which
   `validate-legs` checks and commits.

A caller that posts GL-only legs (interest's aggregate entries,
an opening journal) skips step 2: nothing carries a
`:product-type`, so no control is checked. A caller that posts
both writes them in one call, and `ensure-controls` checks only
the cash-account legs that roll into a control.

## Alternatives Considered

- **Unified ledger — customer cash-accounts ARE GL
  accounts.** No sub-ledger / GL split; every customer
  account sits directly in the chart as a leaf liability.
  Rejected — couples the bank's accounting structure to its
  customer-account shape; restructuring the chart (cost
  centres, segments) would force restructuring per-customer;
  the GL would have one leaf per customer (huge fan-out at
  the top of the chart); reporting "all customer deposits"
  becomes a tree walk rather than a single balance read. The
  sub-ledger / GL split is the standard core-banking pattern
  for good reason — it keeps the bank's books at
  financial-statement granularity and pushes per-customer
  detail down to the instrument layer.
- **Single combined customer-deposits control account.** One
  control (`2010 Customer deposits`) for every customer
  cash-account regardless of product type. Considered —
  cleaner reconciliation; the sub-ledger sums once. Rejected
  — the financial statements want to see current accounts,
  savings accounts, and term deposits as separate line items
  (they're different obligations with different liquidity
  characteristics). Per-product-type controls produce that
  view without a downstream split.
- **Bank-wide single CoA.** One chart shared across every
  bank on the platform. Rejected — each bank is its own legal
  entity with its own accountants; forcing a shared structure
  would impose one bank's choices on all tenants. Per-bank
  CoA is the minimum a multi-tenant banking platform can
  offer.
- **A sub-ledger relaxation — check the legs only when a GL
  account is among them.** The earlier design left a
  customer-only posting unchecked, on the grounds that
  modelling P&L counter-legs for every fee and every reversal
  was a refactor the sub-ledger did not otherwise need.
  Superseded: a cash-account movement moves its control, so a
  customer-only posting is a GL posting too, and a rule that
  applied to some postings and not others bought nothing.
  `validate-legs` now checks every `record`.
- **GL as a derived view, not a stored ledger.** Compute the
  bank's books at query time from customer-account aggregates
  plus product `:balance-sheet-side`. Rejected — works for
  the trivial invariants but cannot represent income,
  expense, or equity movements (no customer leg produces
  them); cannot enforce double-entry; cannot model cost
  centres or segments; cannot support the bank reconciling
  its own books against external accounting. The GL must be
  a stored, authoritative ledger of its own.
- **Chart of accounts as configuration (EDN files), not
  records.** Like the existing per-product-type seed EDNs.
  Rejected — the CoA is a per-bank business-configurable
  artefact; the bank's accountant must be able to add a cost
  centre without a code change. EDN seeds the canonical
  template; the live chart lives in the record store.
- **Caller-supplied paired legs.** Make the caller write both
  the customer leg and its control-account counter-leg
  explicitly. Rejected — pure ceremony, and a fertile source
  of subtle drift bugs where one half lands and the other
  doesn't.
- **Server-side paired legs.** Append a same-side control leg to
  every posted default customer leg, flagged `:control` and left
  out of the balance check. Superseded by
  [ADR-0037](../adr/0037-a-control-accounts-balance-is-the-sum-of-the-balances-that-roll-into-it.md):
  every posting in a bank read and rewrote its control's row, so
  two internal payments in one bank conflicted whichever accounts
  they moved.
- **GL accounts as a `oneof kind` on `CashAccountProduct`,
  spawning a `CashAccount` per GL row.** An intermediate design
  (PR #137): a GL account was a product of
  `kind :general-ledger` plus the one `CashAccount` it spawned,
  so cash and GL accounts shared a single record type and a
  single `get-account` lookup path. Superseded — a GL account
  isn't a product the bank sells, and dressing one up as a
  `CashAccount` under a product meant carrying two disjoint
  optional field sets and guard-reading `gl_code` presence at
  every site. GL accounts are now their **own `LedgerAccount`
  record type** in `ledger-account`: the schema is honest,
  the GL surface is small and read-only, and the shared
  *account-id space* keeps the single-lookup benefit at the leg
  layer without forcing GL accounts to masquerade as cash
  accounts. The earliest form — GL discriminator fields
  directly on `CashAccount` (PR #134) — surfaced the same
  pressure and was the first thing to go.
- **Model the bank's own funds inside a customer-deposit
  control (e.g. a "business current" account rolling into
  2100).** Considered — no new GL line, no new product-type.
  Rejected — the bank's own money isn't a customer deposit;
  lumping it into 2100 overstates customer liabilities and the
  trial balance stops reading true. Its own `3100` equity
  control keeps assets (`1100`), customer liabilities
  (`2100/2200/2300`), and the bank's own funds (`3100`) cleanly
  separable — the one new product-type and GL row earn their
  keep.
- **Inherit an industry-standard taxonomy by default**
  (FRS 102, IFRS, regulator-specific reporting schemas).
  Considered. Out of scope for v1 — the seeded chart is
  intentionally minimal. A future taxonomy-mapping brick
  could attach standard codes to a bank's GL accounts for
  regulatory-report production without forcing the bank to
  adopt the taxonomy as its primary structure.

## Known Limitations

- **No balance sheet, P&L, period closing or export.** The
  chart's list route returns a per-currency trial balance; the
  statements built on top of one are future PRDs and TDDs that
  consume the GL. Closing the books at month-end or year-end —
  posting accumulated income and expense into retained earnings,
  zeroing those accounts for the next period — is not modelled,
  and neither is a feed to a parallel accounting system. A
  changelog relay publishing GL postings to an export topic is
  the shape that would take.
- **No reversal helper for GL postings.** Same gap as
  transactions-and-balances; reversal is "write a new
  transaction with mirrored legs" by hand. The pattern is
  straightforward but unpackaged.
- **No multi-segment / cost-centre dimension.** The GL has
  one hierarchy. Banks that want to slice their books across
  multiple independent dimensions (product line, geography,
  legal entity) need a multi-dimensional GL — out of scope.
- **No regulatory taxonomy mapping.** The five top-level
  classes follow FRS 102's element definitions (asset /
  liability / equity / income / expense), but the chart is
  otherwise entity-specific — neither FRS 102 nor any
  standard prescribes a CoA. Mapping to the statutory
  banking statement format (liquidity-ordered, per the Bank
  Accounts Directive lineage) and to FINREP for prudential
  reporting is left to the bank.
- **Code convention isn't enforced.** A bank can code a
  liability as 5500 or an income as 1000; the system stores
  whatever it's given. Reporting that orders by code will
  produce a confusing view in that case. Worth a soft
  validation (warn if code's leading digit disagrees with
  account-type) before v1 graduates.
- **No CoA authoring.** The chart is fixed at the seeded shape:
  no API adds, re-codes, reclassifies or closes a
  `LedgerAccount`. Extending the chart (cost centres,
  per-scheme 1100 children), re-coding, versioning ("what a code
  meant" at posting time) and a tenant-facing close are all
  future work.
- **Indirect-access modelling is single-sided.** A bank using
  sponsor access sees its 1100 position as its own view of what
  the sponsor holds for it; the sponsor's books carry the
  matching `CPAC` position. Queenswood models the bank-side view
  alone, and a sponsor's reconciliation file would be ingested
  as a feed rather than as a mirrored GL account. A future
  sponsor-integration design would formalise this.
- **No posting out of suspense.** An inbound that cannot be
  matched to an account, or whose account is not operable, is
  parked in 2500 and recorded as a suspended payment by the
  `payment` brick's `record-inbound-suspense`. What is missing
  is the other half: the review queue, the resolution rules and
  the posting that moves an item out of 2500 into the account it
  belonged to. That is a separate TDD.
- **Capitalisation cadence stays operator-driven.** The GL
  posting shape doesn't constrain the operator's freedom to
  choose cadence — see [interest.md](interest.md). A
  daily-capitalisation bank produces daily GL postings; a
  monthly-capitalisation bank produces monthly.
- **GL account code uniqueness is per bank only.** Two
  banks can both have a 2100 — they mean different things.
  Cross-bank reporting (a platform-wide view) would need a
  bank-prefix on display.
- **The canonical template is opinionated.** Banks wanting
  a different starting structure must extend or close-and-
  recreate seeded accounts. A "blank-chart bank with no
  seed" mode would be a useful escape hatch and isn't
  modelled.
- **Queenswood-the-platform's own books are out of scope.**
  Each tenant bank owns its CoA. The platform's own SaaS
  revenue, infrastructure costs, and internal P&L live in a
  separate concern not exposed via tenant APIs and not
  covered here.

## References

- [ADR-0002](../adr/0002-foundationdb-record-layer.md) —
  FoundationDB Record Layer (GL account storage and indices)
- [ADR-0005](../adr/0005-error-handling-with-anomalies.md) —
  Error handling with anomalies (the rejection kinds this TDD
  names are raised and mapped the way that ADR describes)
- [ADR-0021](../adr/0021-changelog-relay.md) — the changelog
  relay (the pattern that GL-export feeds would consume,
  if added)
- [transactions-and-balances.md](transactions-and-balances.md)
  — Transactions and balances (the leg substrate, and
  `validate-legs` described from its side)
- [cash-accounts.md](cash-accounts.md) — Cash accounts (the
  sub-ledger; the `:product-type` that names the control each
  account's balances are summed into, and the own-funds cash
  account)
- [cash-account-products.md](cash-account-products.md) —
  Cash account products (the sub-ledger products — customer
  current / savings / term-deposit and the bank's own-funds
  product — and the payment-rail classification they carry)
- [interest.md](interest.md) — Interest accrual (the accrual
  and capitalisation postings that book the bank's interest
  liability on the 2400 ledger account)
- [payments.md](payments.md) — Payments (inbound settlement
  landing on 1100, pending-outbound asset position 1200)
- [policy-evaluation.md](policy-evaluation.md) — Policy
  evaluation (future capability checks on CoA authoring and
  per-account posting restrictions)
- [scenario-testing.md](scenario-testing.md) — Scenario
  testing (the two runners that assert both standing
  invariants)
- [processor-bricks.md](processor-bricks.md) — Processor
  brick conventions (relevant if a future processor variant
  emerges)
- `ledger-account` brick interface (the `LedgerAccount`
  record type, `find-by-code`, `close-account`, `get-balances`,
  `ensure-controls`, `product-type->control-code`)
- `transaction` / `balance` brick interfaces (the
  leg + bucket substrate, shared across cash and ledger ids)
- `bank` brick interface (chart seeding and own-funds
  account at provisioning)
- `cash-account` / `cash-account-product` brick
  interfaces (the sub-ledger; the `own-funds` product)
- `schema` brick (the `LedgerAccount` proto message and
  GL enums)
- `test-scenarios` / `test-api-scenarios` bricks (the standing
  invariant assertions)
