(ns com.repldriven.queenswood.cash-account-query.interface
  "Read-side (query) surface for cash accounts. Loads and lists
  accounts, optionally enriching with balances and transactions. This
  is the only cash-account brick `api` is allowed to require: it
  exposes no writes. State changes go through `cash-account`
  (commands), which itself reuses these reads inside its own
  transactions.

  The `find-account`, `find-account-by-idempotency-key`,
  `find-accounts-by-party`, `count-by-org` and
  `count-by-org-product-account-type-currency` fns are read
  primitives for the write sibling's transactions; the public reads for
  API consumers are `get-account`, `get-accounts` and
  `get-account-by-bban`."
  (:require
    [com.repldriven.queenswood.cash-account-query.core :as core]
    [com.repldriven.queenswood.cash-account-query.store :as store]))

(defn get-account
  "Load a single cash account. Returns the account map or a
  `:cash-account/not-found` rejection anomaly.

  Args:
  - txn: FDB transaction or db handle.
  - bank-id: owning bank id.
  - account-id: account id.
  - opts (optional): map; `:embed-balances` and
    `:embed-transactions` enrich the result."
  ([txn bank-id account-id]
   (core/get-account txn bank-id account-id))
  ([txn bank-id account-id opts]
   (core/get-account txn bank-id account-id opts)))

(defn get-accounts
  "List cash accounts for a bank. Returns
  `{:accounts [...] :before id|nil :after id|nil}` or an anomaly.

  Args:
  - txn: FDB transaction or db handle.
  - bank-id: owning bank id.
  - opts (optional): map; `:after`, `:before`, `:limit`,
    `:embed-balances`, `:embed-transactions`."
  ([txn bank-id]
   (core/get-accounts txn bank-id))
  ([txn bank-id opts]
   (core/get-accounts txn bank-id opts)))

(defn reduce-accounts-with-balances
  "Stream every account in a bank paired with its balances, in
  account-id order, reducing over `[acc {:account :balances}]`.

  For a pass over a whole bank rather than a page of one: the accounts
  and balances stores are scanned together and merged on account-id,
  so pairing costs no lookup per account. Balances belonging to other
  banks are dropped; an account with no balances arrives with an empty
  vector.

  Not a consistent snapshot — the two scans refill in separate
  transactions, so an account opened mid-pass can appear without its
  balances. Reads only; the caller owns any writing, in whatever
  transactions it wants.

  Args:
  - config: map with :record-db and :record-store.
  - bank-id: owning bank id.
  - f: reducing fn of `[acc {:keys [account balances]}]`. May return
    `reduced`; an anomaly ends the scan and propagates.
  - init: initial accumulator."
  [config bank-id f init]
  (store/reduce-accounts-with-balances config bank-id f init))

(defn get-account-by-bban
  "Return the account matching the given BBAN, or nil.

  Args:
  - txn: FDB transaction or db handle.
  - bban: basic bank account number string."
  [txn bban]
  (core/get-account-by-bban txn bban))

(defn find-account
  "Load a single cash account by primary key without enrichment or
  rejection; returns the raw account map or nil. A read primitive for
  the write sibling's transactions (e.g. its
  `complete-status-transition`, which the changelog relay drives
  through that brick's `events.clj`).

  Args:
  - txn: FDB transaction or db handle.
  - bank-id: owning bank id.
  - account-id: account id."
  [txn bank-id account-id]
  (store/find-account txn bank-id account-id))

(defn find-account-by-idempotency-key
  "Return the CashAccount previously written under `idempotency-key`,
  or nil. A read primitive for the write sibling's open-account
  idempotency read-back.

  Args:
  - txn: FDB transaction or db handle.
  - bank-id: owning bank id.
  - idempotency-key: the command's idempotency key."
  [txn bank-id idempotency-key]
  (store/find-account-by-idempotency-key txn bank-id idempotency-key))

(defn find-accounts-by-party
  "Return every CashAccount held by a party, regardless of status. A
  read primitive for the write sibling's transactions (e.g. the
  merge-party open-accounts guard).

  Args:
  - txn: FDB transaction or db handle.
  - bank-id: owning bank id.
  - party-id: party id."
  [txn bank-id party-id]
  (core/find-accounts-by-party txn bank-id party-id))

(defn count-by-org
  "Count all cash accounts for a bank. A read primitive for the write
  sibling's limit checks.

  Args:
  - txn: FDB transaction or db handle.
  - bank-id: owning bank id."
  [txn bank-id]
  (store/count-by-org txn bank-id))

(defn count-by-version
  "Count the cash accounts pinned to one product version.

  How large a migration's cohort is, answered off an index rather than
  by scanning it — which is what lets a limit on how many accounts a
  migration may move refuse before anything moves rather than discover
  the breach part-way through.

  Args:
  - txn: FDB transaction or db handle.
  - bank-id: owning bank id.
  - version-id: the product version to count."
  [txn bank-id version-id]
  (store/count-by-version txn bank-id version-id))

(defn count-by-org-product-account-type-currency
  "Count cash accounts for a bank grouped by product-type,
  account-type and currency. A read primitive for the write sibling's
  limit checks.

  Args:
  - txn: FDB transaction or db handle.
  - bank-id: owning bank id.
  - product-type: product type keyword.
  - account-type: account type keyword.
  - currency: ISO 4217 currency string."
  [txn bank-id product-type account-type currency]
  (store/count-by-org-product-account-type-currency
   txn
   bank-id
   product-type
   account-type
   currency))
