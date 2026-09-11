(ns com.repldriven.queenswood.ledger-account.interface
  "Bank-owned general-ledger accounts — the chart of accounts a
  customer bank runs its own books on (cash at correspondent,
  customer-deposit controls, interest payable, suspense, etc.). A
  `LedgerAccount` is a flat, bank-owned record distinct from a
  customer `CashAccount`: 1:1 with a chart row, created directly by
  `seed!`, with no product, no versioning, and no command/watcher
  lifecycle. Ledger accounts share the `account-id` space with cash
  accounts, so a `:ledger-account-id` is just another `account-id` to
  `bank-balance` and `bank-transaction` — which is what keeps a
  customer leg and its control-account leg atomic in one posting.

  This brick owns: the product-type to control-code mapping, the
  `new-account` creation call (callers loop it over their own chart
  of accounts), code/id lookups, and the `add-control-legs` paired-leg
  construction posting sites use to fan a customer leg out to its
  control account."
  (:require
    [com.repldriven.queenswood.ledger-account.core :as core]
    [com.repldriven.queenswood.ledger-account.domain :as domain]))

(def
  ^{:doc
    "Map from cash-account product-type keyword to the control
  ledger account's `:gl-account-code` role its balance rolls up into.
  Postings on customer accounts of these product types fan out to a
  matching leg on the control."}
  product-type->control-code
  domain/product-type->control-code)

(defn gl-account-code->gl-code
  "The chart number, as a string, for a `gl-account-code` role (the enum's
  integer value — e.g. `:gl-account-code-suspense` -> `\"2500\"`). The one
  place the bare number is reconstituted, for display/reporting at the API
  edge; code itself resolves accounts by role.

  Args:
  - gl-account-code: a `:gl-account-code-*` role keyword."
  [gl-account-code]
  (domain/gl-account-code->gl-code gl-account-code))

(defn new-account
  "Create one bank-owned `LedgerAccount` from a chart-of-accounts
  `row` in `currency`, along with its single default-posted opening
  balance. Stamps a fresh `led.` id and timestamps. Returns the
  created account, or an anomaly. Callers loop this over their own
  chart and wrap the loop in a transaction for all-or-nothing seeding.

  Gated on the `:ledger-account` create capability: `opts` may carry
  `:policies` (the caller's already-resolved effective policies, as
  bank bootstrap passes); absent that, the bank's effective policies
  are resolved from `txn`. A tier that denies the capability (e.g.
  micro) gets a deny anomaly instead of an account.

  Args:
  - txn: FDB transaction or db handle.
  - bank-id: owning bank id.
  - currency: ISO 4217 currency string.
  - row: chart-of-accounts row (`:gl-account-code`, `:name`,
    `:gl-account-type`, `:gl-account-class`, `:required`).
  - opts (optional): `:policies` to check against."
  ([txn bank-id currency row]
   (core/new-account txn bank-id currency row))
  ([txn bank-id currency row opts]
   (core/new-account txn bank-id currency row opts)))

(defn get-account
  "Return the `LedgerAccount` matching `(bank-id, ledger-account-id)`,
  or nil. Used by the ledger-account API's existence guard.

  Args:
  - txn: FDB transaction or db handle.
  - bank-id: owning bank id.
  - ledger-account-id: ledger account id (`led.<ulid>`)."
  [txn bank-id ledger-account-id]
  (core/get-account txn bank-id ledger-account-id))

(defn close-account
  "Close a bank-owned `LedgerAccount`: an Open -> Closed transition
  guarded on the account's default-posted balance netting to zero and
  the `:ledger-account` close capability. Rejects
  `:ledger-account/invalid-status` if already closed,
  `:gl/non-zero-on-close` if the balance isn't zero. Returns the
  closed account, or an anomaly.

  Gated on the `:ledger-account` close capability the same way
  `new-account` gates open: `opts` may carry `:policies` (the
  caller's already-resolved effective policies); absent that, the
  bank's effective policies are resolved from `txn`.

  Args:
  - txn: FDB transaction or db handle.
  - bank-id: owning bank id.
  - ledger-account-id: ledger account id (`led.<uuidv7>`).
  - opts (optional): `:policies` to check against."
  ([txn bank-id ledger-account-id]
   (core/close-account txn bank-id ledger-account-id))
  ([txn bank-id ledger-account-id opts]
   (core/close-account txn bank-id ledger-account-id opts)))

(defn find-by-code
  "Resolve a ledger account from its `gl-account-code` role and
  `currency`. A bank holds one row per chart role per currency, so the
  role alone does not identify an account. Used by posting sites to find
  counter-leg accounts by role — `:gl-account-code-cash-at-correspondent`,
  `:gl-account-code-interest-payable`, `:gl-account-code-suspense`, etc.

  An absent row is a rejection, not nil: rejects
  `:gl/missing-currency-account` carrying `:message`, `:bank-id`,
  `:gl-account-code` and `:currency` when the bank has no row for the
  triple, and `:ledger-account/closed` when the row it finds is closed.
  Returns the `LedgerAccount` map otherwise.

  Args:
  - txn: FDB transaction or db handle.
  - bank-id: owning bank id.
  - gl-account-code: a `:gl-account-code-*` role keyword.
  - currency: ISO 4217 currency string of the posting."
  [txn bank-id gl-account-code currency]
  (core/find-by-code txn bank-id gl-account-code currency))

(defn list-accounts
  "Return every `LedgerAccount` for `bank-id` (the bank's full chart),
  as a vector.

  Args:
  - txn: FDB transaction or db handle.
  - bank-id: owning bank id."
  [txn bank-id]
  (core/list-accounts txn bank-id))

(defn debit-normal?
  "True for debit-normal account families (asset, expense), false for
  credit-normal (liability, equity, income) — which column a ledger
  account's balance falls in when assembling a trial balance.

  Args:
  - gl-account-type: a `:gl-account-type-*` keyword."
  [gl-account-type]
  (domain/debit-normal? gl-account-type))

(defn add-control-legs
  "Walk `legs` and append a matching control-side leg for every
  customer default-posted leg carrying a sub-ledger `:product-type`,
  resolving the control ledger account from that product type in
  `currency` — the transaction's currency, which every leg of one
  transaction shares. Posting sites call this BEFORE recording the
  transaction so the synthetic control leg lands atomically in the same
  transaction. Legs without a customer product type pass through
  unchanged.

  A leg that fans out and whose control is absent in `currency` or
  closed fails the whole posting, with `:gl/missing-currency-account` or
  `:ledger-account/closed` — the customer leg is never recorded without
  its mirror. Returns the expanded leg vector otherwise.

  Args:
  - txn: FDB transaction or db handle.
  - bank-id: owning bank id.
  - currency: ISO 4217 currency string of the transaction.
  - legs: original transaction legs (customer legs carry
    `:product-type`)."
  [txn bank-id currency legs]
  (core/add-control-legs txn bank-id currency legs))
