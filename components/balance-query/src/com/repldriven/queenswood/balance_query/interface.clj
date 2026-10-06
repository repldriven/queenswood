(ns com.repldriven.queenswood.balance-query.interface
  "Read-side (query) surface for balances: load one balance, list an
  account's balances (enriched with posted/available totals), and the
  pure `trial-balance` aggregation. This is the only balance brick
  `bank-api` (and other readers) may require — it exposes no writes.
  Balance mutation (`apply-legs`, `new-balances`, `accrue`) lives in
  `balance`, which reuses these reads inside its own transactions.

  A cash account's default buckets are the sums of its legs, read from
  the legs' SUM indexes, so every read here returns them with the
  `credit` and `debit` its legs add up to rather than what the bucket's
  row holds. Its other buckets, and every bucket of a general-ledger
  account, are the rows as stored. ADR-0042.

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

(defn list-balances-of
  "List several accounts' raw balance buckets: a map of account id to
  that account's vector of buckets, unenriched. A read primitive for the
  write sibling's apply-legs computation. The legs are summed
  serializably, except for the accounts `opts` names in
  `:snapshot-ids`, whose sums join no read-conflict set, so a posting
  no limit bounds conflicts with nothing else posting to the account.

  Args:
  - txn: FDB transaction or db handle.
  - bank-id: owning bank id, which heads the key.
  - account-ids: owning account ids.
  - opts: optional `{:snapshot-ids #{account-id ...}}`."
  ([txn bank-id account-ids]
   (list-balances-of txn bank-id account-ids {}))
  ([txn bank-id account-ids opts]
   (store/list-balances-of txn
                           bank-id
                           account-ids
                           (set (:snapshot-ids opts)))))

(defn with-leg-sums
  "`balances` with each cash account's default buckets given the
  `credit` and `debit` its legs sum to, read at snapshot in one round
  trip. For a caller that read the rows itself, as a merged scan does.

  Args:
  - txn: an open FDB transaction.
  - balances: balance maps, of any accounts."
  [txn balances]
  (store/with-leg-sums txn balances true))

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

(defn sub-ledger-balance
  "The summed `{:credit :debit}` of the default balances in one status,
  posted unless `opts` names another, of every account of `product-type`
  in `currency` across the bank — the balance of the ledger account
  derived from them. Read from the legs' SUM index grouped by bank and
  product type, at snapshot unless `opts` asks for
  `{:isolation :serializable}`, which a guard deciding on the figure in
  its own transaction needs. An empty group sums to zero.

  Args:
  - txn: FDB transaction or db handle.
  - bank-id: owning bank id.
  - product-type: the sub-ledger product type keyword.
  - currency: ISO 4217 currency string.
  - opts: optional `{:isolation :snapshot | :serializable,
    :balance-status <status keyword>}`."
  ([txn bank-id product-type currency]
   (sub-ledger-balance txn bank-id product-type currency {}))
  ([txn bank-id product-type currency opts]
   (store/sum-bucket txn
                     bank-id
                     product-type
                     currency
                     :balance-type-default
                     (:balance-status opts :balance-status-posted)
                     (:isolation opts :snapshot))))

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
