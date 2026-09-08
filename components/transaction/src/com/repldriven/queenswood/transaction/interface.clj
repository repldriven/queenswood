(ns com.repldriven.queenswood.transaction.interface
  "Double-entry transactions and their legs. Records a transaction
  with its legs in a single FDB transaction, optionally applying
  legs to balances. Returns the transaction map (with `:legs`) or
  an anomaly."
  (:require
    [com.repldriven.queenswood.transaction.system]

    [com.repldriven.queenswood.transaction.core :as core]
    [com.repldriven.queenswood.transaction.store :as store]))

(defn record-transaction
  "Record a transaction and its legs without updating balances.
  Callers must call `apply-legs` separately when balance side-
  effects are required.

  Args:
  - txn: FDB handle or open transaction.
  - data: transaction data (bank-id, idempotency-key,
    transaction-type, currency, reference, legs). `:bank-id` scopes
    the idempotency key, so data without one is rejected with
    `:transaction/missing-bank-id`.

  Returns the transaction map with `:legs` or an anomaly."
  [txn data]
  (core/record txn data))

(defn record-and-post
  "Record a transaction with its legs and apply them to balances in one
  FDB transaction. On a uniqueness violation — the same
  `idempotency-key` recorded before — reads the existing transaction
  back and returns it rather than rejecting, so a retried post is a
  no-op instead of a failure.

  Args:
  - txn: FDB handle or open transaction.
  - bank-id: owning bank id, which heads the key of every balance the
    legs reach and scopes the idempotency key. Assoc'd onto `data`,
    so callers need not repeat it.
  - data: transaction data (idempotency-key, transaction-type,
    currency, reference, legs).

  Returns the transaction map with `:legs` or an anomaly."
  [txn bank-id data]
  (core/record-and-post txn bank-id data))

(defn get-transactions
  "List transaction legs for an account, enriched with the parent
  transaction's type, status, and reference.

  Args:
  - txn: FDB handle or open transaction.
  - account-id: account whose legs to return.
  - opts: optional map with :limit and :order (`:desc` default).

  Returns a vector of leg maps or an anomaly."
  ([txn account-id]
   (store/get-transactions txn account-id))
  ([txn account-id opts]
   (store/get-transactions txn account-id opts)))
