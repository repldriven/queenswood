(ns com.repldriven.queenswood.transaction.interface
  "Double-entry transactions and their legs. Records a transaction
  with its legs in a single FDB transaction, optionally applying
  legs to balances, and co-commits a `transaction-posted` entry — the
  transaction, its legs and, where the payment scheme itself settled
  the posting, the cash account it moved the money through — to the
  transactions store's changelog. Returns the transaction map (with
  `:legs`) or an anomaly."
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
    transaction-type, currency, reference, legs, and optionally
    scheme-account-id). `:bank-id` scopes the idempotency key, so data
    without one is rejected with `:transaction/missing-bank-id`.

  Returns the transaction map with `:legs` or an anomaly."
  [txn data]
  (core/record txn data))

(defn record-transactions
  "Record several transactions and their legs, as `record-transaction`
  records one, saving them together so their records' reads cost one
  round trip. Short-circuits on the first anomaly, recording none.

  Args:
  - txn: FDB handle or open transaction.
  - datas: transaction data maps, as `record-transaction` takes.

  Returns the transaction maps, each with `:legs`, in order, or an
  anomaly."
  [txn datas]
  (core/record-many txn datas))

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

(defn page-transactions
  "One page of an account's transaction legs, newest first by default,
  each enriched with the parent transaction's type, reference and
  creation time. Returns `{:transactions [...] :before key|nil :after
  key|nil}`, a cursor being the leg's `[transaction-id leg-id]` and set
  only where legs lie on that side of the page, or an anomaly.

  Args:
  - txn: FDB handle or open transaction.
  - bank-id: the account's bank.
  - account-id: account whose legs to return.
  - opts: map with `:after`, `:before`, `:limit` (default 1000) and
    `:order` (`:desc` default)."
  [txn bank-id account-id opts]
  (store/page-transactions txn bank-id account-id opts))

(defn get-transactions
  "List transaction legs for an account, enriched with the parent
  transaction's type, reference and creation time.

  Args:
  - txn: FDB handle or open transaction.
  - bank-id: the account's bank.
  - account-id: account whose legs to return.
  - opts: optional map with :limit and :order (`:desc` default).

  Returns a vector of leg maps or an anomaly."
  ([txn bank-id account-id]
   (store/get-transactions txn bank-id account-id))
  ([txn bank-id account-id opts]
   (store/get-transactions txn bank-id account-id opts)))

(defn sum-legs
  "The summed `{:credit :debit}` of every leg recorded against
  `account-id` in one bucket, read from the legs store's SUM index,
  which the Record Layer keeps by atomic mutation as each leg is saved —
  the balance of a ledger account whose postings are summed rather than
  applied to a row. At snapshot unless `opts` asks for
  `{:isolation :serializable}`, which a guard deciding on the figure in
  its own transaction needs. An account with no legs sums to zero.

  Args:
  - txn: FDB handle or open transaction.
  - bank-id: the account's bank.
  - account-id: the account whose legs to sum.
  - balance-type: the bucket's balance type keyword.
  - balance-status: the bucket's balance status keyword.
  - opts: optional `{:isolation :snapshot | :serializable}`."
  ([txn bank-id account-id balance-type balance-status]
   (sum-legs txn bank-id account-id balance-type balance-status {}))
  ([txn bank-id account-id balance-type balance-status opts]
   (store/sum-legs txn
                   bank-id
                   account-id
                   balance-type
                   balance-status
                   (:isolation opts :snapshot))))
