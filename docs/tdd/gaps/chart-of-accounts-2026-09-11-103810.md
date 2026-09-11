# Chart of accounts: gap analysis

Subject: [chart-of-accounts.md](../chart-of-accounts.md) — Chart of
accounts.

## Verdict

**Fail.**

The shape the TDD is built on holds: a GL account is its own flat,
bank-owned `LedgerAccount` record with a typed `GlAccountCode` role
whose integer is the chart number; the nine-row chart is seeded from a
resource once per currency inside the bank's creation transaction,
beside an own-funds house account per currency; the control link is
never stored, a leg's `:product-type` maps to a control role at posting
time and `add-control-legs` appends a same-side, same-amount control leg
before the transaction is recorded; ledger and cash ids share one
account-id space through the balance and transaction bricks; the
payment and interest bricks resolve 1100, 1200, 2500, 5100 and 2400 by
role; and the API is read-only. The verdict fails on what the design
promises around that shape:

- Every lookup by role is blind to currency, so a bank seeded in more
  than one currency posts every GL leg of its second and third
  currencies onto the first currency's accounts. The per-currency
  routing and the `:gl/missing-currency-account` rejection the Currency
  section describes do not exist (F1).
- Neither invariant is verified as written. Nothing reconciles a control
  against its sub-ledger; the one standing assertion is a trial-balance
  tie over posted GL balances, in the domain runner only (F2).
- The control mirror is posted-only, so a pending customer leg never
  reaches a control and the per-status reconciliation cannot hold; the
  brick fans `interest-accrued` legs out to 2400, which the TDD says it
  must not; a missing control is skipped silently rather than rejected
  (F3).
- The double-entry check is a different rule with different names:
  every transaction is checked, control legs are excluded and must
  mirror a posting, and `:gl/imbalanced` exists nowhere (F4).
- The record has a lifecycle, a close transition and a capability gate
  the TDD says it does not have (F5).
- A trial balance is computed and served on the list route and rendered
  in the console, which the TDD scopes out (F6).
- `:iso-cash-account-type` lives on the product, not the account, and
  nothing reads it (F7).
- The data model, the interface docstring and the TDD's own sections
  disagree with each other and with the wire (F8).

The verdict flips to pass when role lookups take a currency and the
Currency section describes what the seed creates, the invariants are
either implemented or restated as the trial-balance tie that runs,
mirroring is documented as posted-only and the `interest-accrued`
fan-out is removed or documented, the double-entry section is rewritten
to the transaction brick's rule, and the lifecycle, the capability gate,
the trial balance and the product-level ISO code are written up.

## What was examined

- The subject TDD, and the sibling TDDs it cites: transactions-and-
  balances, interest, payments, cash-accounts, cash-account-products,
  banks, scenario-testing and policy-evaluation, plus ADR-0005 and
  ADR-0021.
- The `ledger-account` brick: interface, core, domain and store, its
  interface test and its test-resources system file.
- The `LedgerAccount` proto and its five enums, the `ProductType` and
  `IsoCashAccountType` enums, the `CashAccount` and `CashAccountProduct`
  protos, the `Balance` proto, the `LegSide` enum, the record-type
  metadata for `ledger-accounts`, and the `LedgerAccountCapability` in
  the policy proto.
- The seed: `ledgers/general-ledger.edn`, the own-funds product
  template, and the platform and micro capability seeds for ledger
  accounts.
- The consumers: `bank` (chart seeding and house accounts), `payment`
  (core and events, every posting site), `interest` (the run, the chart
  resolver, the accrual and capitalisation entries), `transaction`
  (record, validate-legs, record-and-post), `balance` and
  `balance-domain` (apply-legs, bucket creation, posted and trial
  balance math), `balance-query` (get-balances), and the `fdb` brick's
  compound query.
- The `api` base: the ledger-account routes, queries, components,
  coercion and examples, the simulate handler, the error-status table,
  the transaction leg component and the coercion setup.
- The domain scenario runner's invariants, its verbs for transfers, fees
  and GL assertions, its interest-accrual scenario, and the API scenario
  suite's `ledger-accounts`, `banks`, `payments` and `e2e` folders.
- The console's ledger view.

Nothing was executed. Every finding below was traced in source, and the
ones that predict runtime behaviour say so.

## What matches

- `LedgerAccount` is its own record type in its own `ledger-accounts`
  store, keyed `(bank_id, ledger_account_id)` with a `led.` uuidv7 id,
  carrying `gl_account_code`, `gl_account_type`, `gl_account_class`,
  `required`, an optional `sub_ledger_kind` the seed leaves unset, and
  one currency. `gl_code` is reserved; the `oneof kind` design on
  `CashAccountProduct` is gone and its tags are reserved.
- `GlAccountCode`'s integer value is the chart number, `find-by-code`
  resolves by role through the `LedgerAccount_by_bank_gl_account_code`
  index, and `gl-account-code->gl-code` reconstitutes the string only in
  the API's `->api` projection. No posting site carries a literal code.
- The seed matches the nine-row table, row for row, type and class
  included, with 2400 a control and 5100 an expense detail. `new-bank`
  loops `new-account` over the resource for every currency inside its
  own `store/transact`, and the bank brick's rollback test proves a
  failed create leaves no chart behind.
- Each ledger account opens one `default / posted` balance tagged
  `:product-type-general-ledger`, which is how read sites tell the
  bank's books from a customer instrument.
- `product-type->control-code` is the TDD's table: current, savings and
  term-deposit to 2100, 2200 and 2300, own-funds to 3100. The control
  link is not stored — tag 20 on `CashAccount` is reserved with a
  comment saying so.
- The house account is an ordinary `CashAccount` on the bank's org party
  under an internal own-funds product created and published per
  currency at provisioning, and the API scenario for a three-currency
  bank asserts one per currency.
- `add-control-legs` appends a same-side, same-amount control leg for a
  customer default posted leg, resolves the control by role, and every
  payment posting site calls it inside the same transaction before
  `record-transaction`.
- Normal side is derived: `debit-normal?` is a predicate over the type,
  and nothing stores it.
- Inbound settlement is DR 1100 / CR creditor with the control
  fan-out; an unmatched BBAN, or one whose account is not operable,
  parks DR 1100 / CR 2500; the outbound reservation and its settlement
  drain 1200 to 1100; each `find-by-code` that returns nil is an
  anomaly naming the missing row.
- Accrual and capitalisation resolve the chart before touching an
  account, post the bank's side in aggregate at close, and never call
  `add-control-legs`.
- The transaction API's leg component accepts a cash-account or a
  ledger-account id, and `/ledger-accounts` is list, get and balances
  with `:ledger-account/not-found` as a 404.

## Findings

Severity is High where a stated guarantee is broken, Medium where the
design and the code disagree in a way a reader would act on, Low where
the document is incomplete.

### F1. High — a per-currency chart, resolved without a currency

The Currency section says a posting in currency X routes to "the
matching X-denominated control account child" under a summary parent,
and a bank without one "rejects with `:gl/missing-currency-account`".
The seed creates flat rows, one per code per currency, no summary
parents — the three-currency API scenario asserts twenty-seven — and
every lookup by role ignores the currency. The index is
`[bank_id, gl_account_code]`; `store/find-by-code` runs the compound
query that caps the planner at one result and returns the first, and
equal index keys order by primary key, so the winner is the first
currency the create loop seeded. `add-control-legs` resolves the
control the same way. The interest brick's `chart/account-id` takes the
first row matching the code across `list-accounts`, so a run's
`accrual-accounts` names one 5100 and one 2400 for every currency it
posts. The simulate handler and the domain runner's verbs do the same.

A USD inbound to a bank seeded EUR first therefore debits the EUR-coded
1100 in a USD bucket, which the balance brick opens on demand, and
credits the EUR-coded 3100 or 21x0; `get-balances` labels its totals
with the first bucket's currency and `posted-balance` sums every posted
bucket regardless of currency, so the list route's `posted-balance` and
its trial balance mix currencies under one label, and because both sides
land on same-labelled accounts the tie still holds and hides it. The
customer templates allow GBP alone, so today only the house accounts can
hold another currency, which is why nothing has tripped. Traced, not
executed. Evidence: `find-by-code` in the ledger-account brick's
`store.clj`, `control-leg` in its `core.clj`, `account-id` in the
interest brick's `domain/chart.clj`, `new-ledger-accounts` in the bank
brick's `core.clj`, `get-balances` in the balance-query brick's
`core.clj`, `new-zero-balance` in the balance brick's `domain.clj`, the
`ledger-accounts` entry in
[fdb-record-types.yml](/components/resources/resources/system/fdb-record-types.yml),
and the `list-multi-currency` API scenario.

Fix: add `currency` to the index and to `find-by-code`,
`add-control-legs` and the interest chart resolver, reject when the
currency's row is missing, and rewrite the Currency section to the flat
per-currency rows the seed creates or seed the summary parents it
describes. Add a scenario that posts in a bank's second currency and
asserts the leg landed on that currency's account.

### F2. High — neither invariant is verified as written

The Two Invariants section says both "are asserted as `nom-test>`
assertions inside `test-scenarios` and run on every scenario", that the
second is "verified on every scenario", and that the interest
reconciliation is "asserted in interest-specific scenarios". The domain
runner's `invariants.clj` asserts one thing after every step: that each
bank's trial balance ties per currency, summing the posted balance of
every ledger account by its normal side, with `clojure.test/is` and not
`nom-test>`, and treating a balance read that fails as zero. That is
neither invariant. Nothing anywhere sums the open cash accounts of a
product type and compares the total to the control's bucket, and nothing
sums the customer `interest-accrued` buckets against 2400. The
interest-accrual scenario pins four fixed GL balances after a run, which
is a regression check, not a reconciliation. The API scenario runner,
which is where payments, holds, suspense and the end-to-end path are
driven, carries no standing invariant at all, and the property model
excludes the GL. The scenario-testing TDD the section points to for
"the testing mechanism the invariant uses" does not mention the chart
of accounts. Evidence: `assert-bank-ties` and `posted-value` in the
scenario brick's `invariants.clj`, `run-commands` in its
`interface.clj`, the `assert-gl-balance` verb, and the
`interest-accrual` domain scenario.

Fix: implement the sub-ledger ↔ control reconciliation as a standing
assertion in both runners, or restate the section as the trial-balance
tie that runs and say where it runs; add the interest reconciliation to
the interest scenarios; describe the tie in the scenario-testing TDD.

### F3. High — the mirror is posted-only, and it fans out what it should not

The Paired-leg section says the control leg mirrors the customer leg's
`(balance-type, balance-status, currency)`, that "status mirroring means
a pending customer credit shows up as a pending control-account credit",
and the bucket table gives the deposit controls three statuses; the
second invariant reconciles "per (balance-status, currency)". `fans-out?`
accepts only `:balance-status-posted`, and `control-leg` always writes
`default / posted` on the control. The outbound reservation, which the
payment brick posts as pending-outgoing on both the debtor and 1200,
never reaches 2100, so no control has ever had a pending bucket and the
per-status reconciliation cannot hold except at posted. The TDD
contradicts itself here: "only the `default` balance-type mirrors, and
it no longer mirrors per leg" sits beside the same-status mirror table.

The same section says legs against `interest-accrued` "do not auto-pair
to a control bucket", and the Known Limitations repeat it "by design".
`fans-out?` accepts `:balance-type-interest-accrued`, and
`balance-type->control-code` routes it to 2400 ahead of the product-type
mapping. No production caller passes such a leg — the interest run
writes accrual as a balance with no transaction and posts the bank's
side in aggregate — so the mapping is dead in production and live in the
brick's contract: a caller that did pass one would book 2400 twice, once
per leg and once at close.

The Currency section says a missing account rejects. `control-leg`
returns nil "when the control account isn't seeded yet" and the leg
passes through unpaired, so a chart missing a control posts the customer
side alone and the books drift silently; only a closed control rejects.
Traced, not executed. Evidence: `fans-out?`, `balance-type->control-code`
and `ensure-open` in the ledger-account brick's `domain.clj`,
`control-leg` in its `core.clj`, `outbound-payment->transaction` in the
payment brick's `domain.clj`, and `sweep` in the interest brick's
`capitalize.clj`.

Fix: rewrite the mirror as posted-only, drop the pending statuses from
the control bucket table and restrict the second invariant to posted;
delete `balance-type->control-code` or document that `interest-accrued`
fans out and remove the aggregate entry, one or the other; reject an
unseeded control.

### F4. Medium — a different double-entry rule, under different names

The Double-entry section says legs are checked only "if any leg targets
a GL account", that the check covers "the full leg-set", and that
imbalance rejects `:gl/imbalanced`; the References say ADR-0005 covers
`:gl/imbalanced`, `:gl/non-zero-on-close` and
`:gl/missing-currency-account`. `validate-legs` runs on every
`record`, customer-only or not; it removes `:control` legs, requires the
rest to balance with `:transaction/legs-unbalanced`, and requires each
control leg to duplicate a posting leg by side and amount with
`:transaction/control-leg-mismatch`. So the asymmetry does not exist, the
control legs are outside the sum rather than inside it, and neither
`:gl/imbalanced` nor `:gl/missing-currency-account` appears anywhere in
the workspace; ADR-0005 names none of the three. The worked example's
"debit 100 = credit 100" is true of the two posting legs and false of
the three legs shown, whose credits sum to 200. The transactions-and-
balances TDD already describes the code's rule as adopted, so the two
TDDs disagree. Evidence: `validate-legs` in the transaction brick's
`domain.clj` and its `domain_test.clj`, the Alternatives of
[transactions-and-balances.md](../transactions-and-balances.md), and
[ADR-0005](../../adr/0005-error-handling-with-anomalies.md).

Fix: rewrite the section around the transaction brick's rule — every
transaction balances over its non-control legs, each control leg
mirrors a posting — name the two kinds, fix the example, and drop the
ADR-0005 claim or add the kinds to it.

### F5. Medium — a lifecycle, a close and a capability gate the TDD denies

The Lifecycle section says a `LedgerAccount` "has no command/event
lifecycle" and "is effectively immutable", that close-out "is future
work", and the Policy Integration section says "there is no posting or
authoring capability gate on them today" and sketches a future
`:gl-account` kind. The proto carries `LedgerAccountStatus` at tag 13,
unset read as open; `close-account` is an Open to Closed transition
guarded by `:ledger-account/invalid-status`, `:gl/non-zero-on-close`
over the default posted bucket, and a `:ledger-account` close
capability; `find-by-code` and the control fan-out reject
`:ledger-account/closed`. `new-account` is gated on the
`:ledger-account` open capability, which the platform seed allows and
the micro seed denies, so seeding a micro bank works only because the
bank brick passes the bootstrap policies through. The transition has no
route, no command and no production caller — its five tests are its only
exercise — and `:ledger-account/invalid-status` has no 409 entry in the
API's status table. Evidence: `close-account` in the ledger-account
brick's `core.clj`, `close` and `new-ledger-account` in its
`domain.clj`,
[ledger-account.proto](/components/schema/resources/schemas/ledger-accounts/ledger-account.proto),
[ledger-accounts.yml](/components/resources/resources/policies/micro/restricted/capabilities/ledger-accounts.yml)
under the micro capabilities, `new-ledger-accounts` in the bank brick's
`core.clj`, and `rejection-status-overrides` in the api base's
`errors.clj`.

Fix: document the two states, the close guard and the `:ledger-account`
capability with its two actions and the bootstrap bypass; either give
close a route and the 409 mapping or say it is in-process only; replace
the future `:gl-account` sketch with what ships.

### F6. Medium — a reporting surface the TDD scopes out already ships

The Objective lists trial balance among the out-of-scope "tenant-visible
reporting surfaces", and the Known Limitations open with "No reporting
surfaces". `list-ledger-accounts` reads every account's balances, attaches
a derived `posted-balance` to each, and returns a per-currency
`trial-balance` block of debit, credit and account count; the
`TrialBalanceEntry` component documents it; the console renders it in a
ledger view. Evidence: `list-ledger-accounts` and `trial-balance-entry`
in the api base's ledger-account `queries.clj`, `TrialBalanceEntry` in
its `components.clj`, and the console's `LedgerAccounts.svelte`.

Fix: bring the trial balance into scope, describe the list route's
enrichment and the sign convention, and narrow the limitation to the
balance sheet and P&L.

### F7. Medium — the ISO code is on the product, and nothing reads it

The ISO 20022 section says customer cash-accounts "carry an
`:iso-cash-account-type` field, defaulted from `:product-type` at open
time and override-able", and that it "is read when emitting outbound ISO
20022 messages". The field is `iso_cash_account_type` on the product
template and on each product version, set from the template's YAML at
creation; the `CashAccount` proto has no such field, the open command
takes no override, and no emitter, adapter or handler reads it. `SACC`
and `CPAC` are enum values with no writer. Evidence: the
`IsoCashAccountType` enum and tags 19 and 8 in
[account-product.proto](/components/schema/resources/schemas/cash-account-products/account-product.proto),
the three customer templates under
`cash-account-product-templates`, and `product-fields` in the
cash-account-product brick's `domain.clj`.

Fix: move the section to the products TDD as a per-version attribute,
or move the field to the account and add the override; say nothing
reads it until an emitter does.

### F8. Medium — the data model, the docstrings and the wire disagree

The proto block omits `status` (tag 13). The API `LedgerAccount`
component does not declare it either, and the interceptor chain has no
response coercion, so `status` reaches every list and get body
undocumented. The interface docstring says accounts are "created directly
by `seed!`" and that a ledger id is "just another `account-id` to
`bank-balance` and `bank-transaction`"; `seed!` is a helper in the
brick's own test and the two brick names are two renames old. The
Lifecycle section repeats "`seed!` via `new-account`". The Bricks
Involved section says `payment / interest` "fan out via
`add-control-legs`", while the Paired-leg section says interest "is no
longer such a caller"; the code agrees with the second. The test chart
is seven rows with 2400 as a detail account and no 3100 or 5100, and its
docstring calls it a mirror of what the bank seeds. Evidence: the ns
docstring in the ledger-account brick's `interface.clj`, `LedgerAccount`
in the api base's ledger-account `components.clj`, `coercion` in its
`api.clj`, and `template` in the brick's `interface_test.clj`.

Fix: add `status` to the proto block and the API component, fix the two
docstrings and the section that names `seed!`, reconcile Bricks Involved
with Paired-leg, and make the test chart the seed.

### F9. Low — 1200 has a pending bucket, and buckets open on demand

The bucket table says 1100, 1200, 2400 and 2500 carry one
`default / posted` bucket each and "the seeded chart uses posted-only
throughout". The outbound reservation credits 1200 at
`default / pending-outgoing`, settlement and reversal debit it there, and
the balance brick's `new-zero-balance` opens any `(balance-type,
balance-status)` a leg names, tagging a leg with no product type as
`:product-type-general-ledger`. So 1200 carries two buckets on any bank
that has sent a payment, and any GL account gains a bucket the first
time a leg names one. Evidence: `outbound-payment->transaction` and
`outbound-settlement->transaction` in the payment brick's `domain.clj`,
and `apply-leg-to-balances` in the balance brick's `domain.clj`.

Fix: list 1200's second bucket, say buckets are created by the first leg
that names them, and drop "posted-only throughout".

### F10. Low — the Known Limitations are partly stale

"No reporting surfaces" (F6), "no posting or authoring capability gate"
(F5), "interest-accrued has no per-leg control mirror by design" (F3)
and "no CoA authoring or close-out" (F5, for close) are false as
written. "Suspense workflow isn't designed" understates it: unmatched
and non-operable inbounds are parked in 2500 and recorded as suspended
payments; only posting out is missing. The rest — no period closing, no
reversal helper, one hierarchy, no export, no taxonomy, no code
enforcement, single-sided `CPAC` — hold. Evidence:
`record-inbound-suspense` in the payment brick's `events.clj`.

Fix: rewrite the list to the limitations that hold.

## Missing evidence

- No test posts in a second currency on a multi-currency bank. The two
  multi-currency scenarios list the chart and assert zero ties; nothing
  moves money in EUR or USD, so F1 is unproven end to end.
- Nothing tests `add-control-legs` with a pending customer leg, with an
  `interest-accrued` leg, or against a chart missing the control — the
  three branches F3 turns on. The one fan-out test's leg carries
  `:side :side-credit`, not the `:leg-side-credit` value the transaction
  and balance bricks read, so the mirrored side has never met a real
  posting.
- No test, in either runner, reconciles a control against the sum of its
  cash accounts (F2). The trial-balance tie runs only in the domain
  runner; the API suite, where every real payment path is driven, has no
  standing invariant.
- The domain runner's `:outbound-transfer` verb posts CREDIT 1200 posted
  / DEBIT customer posted, a shape the payment brick never produces —
  its reservation is pending-outgoing on both legs — so the model-
  equality tests do not cover the real reservation or its settlement.
- `close-account` has no production caller; nothing drives a payment
  into a closed control through `find-by-code`'s gate.
- No API scenario retrieves a single ledger account or exercises its
  404; only list and balances are driven.
- No test covers `list-by-bank`'s cap of one thousand rows, or
  `gl-account-code->gl-code`.
- The ledger-account test chart never seeds 3100 or 5100, so the
  own-funds fan-out is exercised only through the bank and e2e
  scenarios, and 2400 is seeded there as a detail account.

## Recommended fixes

In order:

1. Make role lookups currency-aware — the index, `find-by-code`,
   `add-control-legs` and the interest chart resolver — reject a missing
   currency row, and rewrite the Currency section to the flat rows the
   seed creates; add a second-currency posting scenario (F1).
2. Implement the sub-ledger ↔ control reconciliation in both runners, or
   restate the invariants as the trial-balance tie and say where it
   runs; add the interest reconciliation to the interest scenarios; make
   the scenario-testing TDD describe it (F2).
3. Document posted-only mirroring, drop the pending control buckets and
   restrict the second invariant to posted; delete the `interest-accrued`
   fan-out or document it and remove the aggregate entry; reject an
   unseeded control (F3).
4. Rewrite the Double-entry section to the transaction brick's rule and
   kinds, fix the example, and correct the ADR-0005 claim (F4).
5. Document the status, the close transition, the `:ledger-account`
   capability and the bootstrap bypass; give close a route and a 409
   mapping or mark it in-process (F5).
6. Bring the trial balance into scope and describe the list route's
   enrichment (F6); move the ISO code section to the products TDD or the
   field to the account (F7).
7. Complete the proto block and the API component, fix the stale
   docstrings and the internal contradiction, and make the test chart
   the seed (F8); correct the bucket table (F9) and the Known
   Limitations (F10).
8. Add tests: a pending leg, an `interest-accrued` leg and a missing
   control through `add-control-legs` with real side values; a control
   reconciliation; the real outbound reservation shape in the domain
   runner; a single-account GET and its 404; a closed control met by a
   payment.

## References

- [chart-of-accounts.md](../chart-of-accounts.md) — Chart of accounts
- [transactions-and-balances.md](../transactions-and-balances.md) —
  Transactions and balances
- [interest.md](../interest.md) — Interest
- [payments.md](../payments.md) — Payments
- [cash-accounts.md](../cash-accounts.md) — Cash accounts
- [cash-account-products.md](../cash-account-products.md) — Cash
  account products
- [banks.md](../banks.md) — Banks
- [scenario-testing.md](../scenario-testing.md) — Scenario testing
- [policy-evaluation.md](../policy-evaluation.md) — Policy evaluation
- [ADR-0005](../../adr/0005-error-handling-with-anomalies.md) — Error
  handling with anomalies
- [ADR-0021](../../adr/0021-changelog-relay.md) — The changelog relay
