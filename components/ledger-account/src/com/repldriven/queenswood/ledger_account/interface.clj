(ns com.repldriven.queenswood.ledger-account.interface
  "Bank-owned general-ledger accounts — the chart of accounts a
  customer bank runs its own books on (cash at correspondent,
  customer-deposit controls, interest payable, suspense, etc.). A
  `LedgerAccount` is a flat, bank-owned record distinct from a
  customer `CashAccount`: 1:1 with a chart row, created a row at a
  time by `new-account`, with no product, no versioning, and no
  command/watcher lifecycle. Ledger accounts share the `account-id`
  space with cash accounts, so a `:ledger-account-id` is just another
  `account-id` to `balance` and `transaction`. A control account holds
  no balance of its own: its balance is the sum of the balances of the
  cash accounts whose product type rolls into it.

  This brick owns: the product-type to control-code mapping, the
  `new-account` creation call (callers loop it over their own chart
  of accounts), code/id lookups, ledger accounts' balances, and
  `ensure-controls`, which posting sites use to refuse a customer leg
  whose control is missing or closed."
  (:require
    [com.repldriven.queenswood.ledger-account.core :as core]
    [com.repldriven.queenswood.ledger-account.domain :as domain]))

(def
  ^{:doc
    "Map from cash-account product-type keyword to the control
  ledger account's `:code` role its balance rolls up into.
  The control's balance is the sum of the default posted balances of
  the cash accounts of these product types."}
  product-type->control-code
  domain/product-type->control-code)

(defn chart-number
  "The chart number, as a string, for a `code` role (the enum's
  integer value — e.g. `:ledger-account-code-suspense` -> `\"2500\"`). The one
  place the bare number is reconstituted, for display/reporting at the API
  edge; code itself resolves accounts by role.

  Args:
  - code: a `:ledger-account-code-*` role keyword."
  [code]
  (domain/chart-number code))

(defn new-account
  "Create one bank-owned `LedgerAccount` from a chart-of-accounts
  `row` in `currency`, along with its single default-posted opening
  balance, unless it is a control, whose balance is summed from its
  sub-ledger. Stamps a fresh `led.` id and timestamps. Returns the
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
  - row: chart-of-accounts row (`:code`, `:name`).
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

(defn get-balances
  "A ledger account's balances with their posted and available totals,
  `{:balances [...] :posted-balance {...} :available-balance {...}}`, as
  the balance brick derives them for any account. A control account's
  single default-posted balance is the sum of its sub-ledger's, read
  from the balances store's indexes rather than a stored row. Returns
  the map or an anomaly.

  Args:
  - txn: FDB transaction or db handle.
  - bank-id: owning bank id.
  - account: the `LedgerAccount` map."
  [txn bank-id account]
  (core/get-balances txn bank-id account))

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
  "Resolve a ledger account from its `code` role and
  `currency`. A bank holds one row per chart role per currency, so the
  role alone does not identify an account. Used by posting sites to find
  counter-leg accounts by role — `:ledger-account-code-cash-at-correspondent`,
  `:ledger-account-code-interest-payable`, `:ledger-account-code-suspense`, etc.

  An absent row is a rejection, not nil: rejects
  `:gl/missing-currency-account` carrying `:message`, `:bank-id`,
  `:code` and `:currency` when the bank has no row for the
  triple, and `:ledger-account/closed` when the row it finds is closed.
  Returns the `LedgerAccount` map otherwise.

  Args:
  - txn: FDB transaction or db handle.
  - bank-id: owning bank id.
  - code: a `:ledger-account-code-*` role keyword.
  - currency: ISO 4217 currency string of the posting."
  [txn bank-id code currency]
  (core/find-by-code txn bank-id code currency))

(defn prefetch
  "Start loading, without waiting, the ledger accounts a posting in
  `currency` on accounts of `product-types` goes on to read: each
  product type's control, as `ensure-controls` would, and, unless `opts`
  says `:journal? false`, 1100, 1200 and 5100, as `find-by-code` and
  `stored-legs` would. Only an account whose id the transaction's
  `:ledger-account` cache holds is started; the rest are read as usual
  when asked for. Returns nil, or an anomaly when the loads could not be
  started.

  Args:
  - txn: FDB transaction or db handle.
  - bank-id: owning bank id.
  - currency: ISO 4217 currency string of the posting.
  - product-types: the product types of the cash accounts it posts to.
  - opts: optional `{:journal? false}` for a posting that names no
    journal account and calls no `stored-legs`."
  ([txn bank-id currency product-types]
   (core/prefetch txn bank-id currency product-types {}))
  ([txn bank-id currency product-types opts]
   (core/prefetch txn bank-id currency product-types opts)))

(defn list-accounts
  "Return every `LedgerAccount` for `bank-id` (the bank's full chart),
  as a vector.

  Args:
  - txn: FDB transaction or db handle.
  - bank-id: owning bank id."
  [txn bank-id]
  (core/list-accounts txn bank-id))

(defn list-accounts-with-balances
  "Return the bank's chart paired with each account's balances, as a
  vector of `{:account LedgerAccount :balances [Balance ...]}` in
  account-id order, or an anomaly. Read in one transaction, so every
  balance is as of one moment and the chart's trial balance ties: the
  stored balances in one round trip, and a derived account's balances
  its one balance summed from its sub-ledger or its legs, as
  `get-balances` returns.

  Args:
  - txn: FDB transaction or config map.
  - bank-id: owning bank id."
  [txn bank-id]
  (core/list-accounts-with-balances txn bank-id))

(defn account-class
  "The class of the account in a `code` role, from the thousand
  of its chart number: `:ledger-account-class-asset`, `-liability`,
  `-equity`, `-income` or `-expense`.

  Args:
  - code: a `:ledger-account-code-*` keyword."
  [code]
  (domain/account-class code))

(defn account-type
  "The type of the account in a `code` role:
  `:ledger-account-type-control` for one standing for a sub-ledger —
  the deposit and own-funds controls and 2400 interest-payable — and
  `:ledger-account-type-detail` otherwise.

  Args:
  - code: a `:ledger-account-code-*` keyword."
  [code]
  (domain/account-type code))

(defn debit-normal?
  "True for an account whose class is debit-normal (asset, expense),
  false for a credit-normal one (liability, equity, income) — which
  column a ledger account's balance falls in when assembling a trial
  balance.

  Args:
  - account: a `LedgerAccount` map, read for its `:code`."
  [account]
  (domain/debit-normal? account))

(defn ensure-controls
  "Check the control every posted default customer leg in `legs` rolls
  into, by its sub-ledger `:product-type`, in `currency` — the
  transaction's currency, which every leg of one transaction shares.
  Posting sites call this before recording the transaction. Returns
  `legs` unchanged: a control holds no balance of its own, its balance
  being the sum of its sub-ledger's, so a posting adds no leg for it.

  A leg whose control is absent in `currency` or closed fails the whole
  posting, with `:gl/missing-currency-account` or
  `:ledger-account/closed`. A leg in any other balance-type or
  balance-status, and a leg without a customer product type, is not
  checked.

  Args:
  - txn: FDB transaction or db handle.
  - bank-id: owning bank id.
  - currency: ISO 4217 currency string of the transaction.
  - legs: transaction legs (customer legs carry `:product-type`)."
  [txn bank-id currency legs]
  (core/ensure-controls txn bank-id currency legs))

(defn stored-legs
  "The legs of a recorded transaction whose balances are stored, for
  `balance/apply-legs`: `legs` less those on a ledger account that keeps
  no balance row — 1200 pending-outbound, which mirrors the customers'
  pending-outgoing balances, and 1100 cash-at-correspondent and 5100
  interest-expense, which sum their own legs. Such a leg stays in the
  transaction, which balances with it, and in the journal; it is only
  left out of the balance writes, so no posting rewrites a row every
  payment shares. One of those accounts the bank has no row for in
  `currency` names no leg, so it is passed over. See ADR-0038, ADR-0039
  and ADR-0042.

  Args:
  - txn: FDB transaction or db handle.
  - bank-id: owning bank id.
  - currency: ISO 4217 currency string of the transaction.
  - legs: the transaction's legs."
  [txn bank-id currency legs]
  (core/stored-legs txn bank-id currency legs))
