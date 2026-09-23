(ns com.repldriven.queenswood.balance-query.interface
  "Read-side (query) surface for balances: load one balance, list an
  account's balances (enriched with posted/available totals), and the
  pure `trial-balance` aggregation. This is the only balance brick
  `bank-api` (and other readers) may require — it exposes no writes.
  Balance mutation (`apply-legs`, `new-balances`, `set-carry`) lives in
  `bank-balance`, which reuses these reads inside its own transactions.

  `find-balance` and `list-balances` are read primitives for the write
  sibling's transactions; `get-balance` / `get-balances` are the public
  reads."
  (:require
    [com.repldriven.queenswood.balance-query.core :as core]
    [com.repldriven.queenswood.balance-query.store :as store]

    [com.repldriven.queenswood.balance-domain.interface :as domain]))

(defn get-balance
  "Look up a single balance by its composite primary key. Returns
  the balance map or a `:balance/not-found` rejection anomaly.

  Args:
  - txn: FDB transaction or db handle.
  - bank-id: owning bank id, which heads the key.
  - account-id: owning account id.
  - balance-type: balance-type keyword.
  - currency: ISO 4217 currency string.
  - balance-status: balance-status keyword."
  [txn bank-id account-id balance-type currency balance-status]
  (store/get-balance txn
                     bank-id
                     account-id
                     balance-type
                     currency
                     balance-status))

(defn get-balances
  "List all balances for an account, enriched with derived
  posted-balance and available-balance totals. Returns
  `{:balances [...] :posted-balance {...} :available-balance {...}}`
  or an anomaly.

  Args:
  - txn: FDB transaction or db handle.
  - bank-id: owning bank id, which heads the key.
  - account-id: owning account id."
  [txn bank-id account-id]
  (core/get-balances txn bank-id account-id))

(defn totals
  "Derive an account's posted-balance and available-balance totals from
  its balance buckets, as `get-balances` does after its read: returns
  `{:balances [...] :posted-balance {...} :available-balance {...}}`.
  Pure, for a caller that already holds the buckets.

  Args:
  - balances: the account's balance maps, all in one currency."
  [balances]
  (core/totals balances))

(defn list-balances
  "List an account's raw balance buckets (a vector, unenriched). A read
  primitive for the write sibling's apply-legs computation.

  Args:
  - txn: FDB transaction or db handle.
  - bank-id: owning bank id, which heads the key.
  - account-id: owning account id."
  [txn bank-id account-id]
  (store/get-balances txn bank-id account-id))

(defn find-balance
  "Load a single balance by composite key without rejecting when
  absent; returns the balance map or nil. A read primitive for the
  write sibling's transactions.

  Args:
  - txn: FDB transaction or db handle.
  - bank-id: owning bank id, which heads the key.
  - account-id: owning account id.
  - balance-type: balance-type keyword.
  - currency: ISO 4217 currency string.
  - balance-status: balance-status keyword."
  [txn bank-id account-id balance-type currency balance-status]
  (store/find-balance txn
                      bank-id
                      account-id
                      balance-type
                      currency
                      balance-status))

(def
  ^{:doc
    "Name of the FDB store balances live in. Exposed for callers that
  pair balances with another store in a single `fdb/merge-scan` and so
  have to name it, rather than reaching them one account at a time."}
  store-name
  store/store-name)

(defn trial-balance
  "Aggregate account-level posted balances into a per-currency trial
  balance — `[{:currency :debit :credit :accounts}]`, one block per
  currency, Sigma-debit equal to Sigma-credit when the currency's books
  balance.

  Args:
  - entries: collection of `{:currency :normal-side :value}`, where
    `:normal-side` is `:debit`/`:credit` (the account's normal side) and
    `:value` is the credit-positive posted net."
  [entries]
  (domain/trial-balance entries))
