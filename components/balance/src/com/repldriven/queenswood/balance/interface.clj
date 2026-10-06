(ns com.repldriven.queenswood.balance.interface
  "Balance write side: create an account's balance buckets, apply
  transaction legs to them (with policy-gated capability and limit
  checks), and advance an accrued bucket from a frozen row. Buckets
  are keyed by `(account-id, balance-type, currency, balance-status)`.

  Reads (lookup, listing with posted/available totals, trial-balance)
  live in `bank-balance-query`, which this brick reuses inside its own
  transactions. These writes are called by other bricks' processors
  (cash-account open, payment/interest/transaction posting) inside their
  FDB transactions, not as standalone commands."
  (:require
    [com.repldriven.queenswood.balance.core :as core]))

(defn new-balances
  "Create multiple balances in a single transaction; short-circuits
  on the first anomaly.

  Args:
  - txn: FDB transaction or db handle.
  - bank-id: owning bank id, which heads each balance's key.
  - data: collection of balance creation maps, each with `:account-id`,
    `:product-type`, `:balance-type`, `:balance-status`, `:currency`.
  - opts (optional): map; `:policies` overrides policy resolution."
  ([txn bank-id data]
   (core/new-balances txn bank-id data))
  ([txn bank-id data opts]
   (core/new-balances txn bank-id data opts)))

(defn apply-legs
  "Apply each leg to its target balance (with the
  `:balance-action-apply` capability check) and run the computed
  `:available` limit check per affected account. Returns nil on
  success or an anomaly. `transaction-type` scopes which limits
  fire via the limit's `transaction-type` filter.

  A cash account's default bucket is the sum of its legs, so its row is
  not rewritten: a leg on one must already be recorded in this
  transaction, which is how its sum moves, and the check reads the sum
  less the posting's own legs as the balance before it. An account the
  posting only adds to is summed at snapshot, so it conflicts with no
  other posting adding to it. Every other bucket's row is read and
  rewritten as before. ADR-0042.

  Args:
  - txn: FDB transaction or db handle.
  - bank-id: owning bank id, which keys the balances this reads and
    opens a bucket a leg reaches first.
  - legs: collection of leg maps; each carries `:account-id`,
    `:balance-type`, `:balance-status`, `:side`, `:amount`.
  - transaction-type: transaction-type keyword (e.g.
    `:transaction-type-internal-transfer`).
  - opts (optional): map; `:policies` overrides policy resolution."
  ([txn bank-id legs transaction-type]
   (core/apply-legs txn bank-id legs transaction-type))
  ([txn bank-id legs transaction-type opts]
   (core/apply-legs txn bank-id legs transaction-type opts)))

(defn accrue
  "Advance a bucket the caller already holds: raise its `:credit` by
  `whole-units` and replace its `:credit-carry`, writing the row back
  without reading it first. Returns the updated balance or an anomaly.

  Unlike `apply-legs` this reads nothing and checks no policy, so it
  is sound only where the caller froze the row in an earlier
  transaction and no other writer can have touched it since. That
  holds for an interest-accrued bucket, which only the interest pass
  credits and capitalisation sweeps. It does not hold for anything
  payments reach, where a read-modify-write inside the writing
  transaction is what keeps concurrent postings from being lost.

  Args:
  - txn: FDB transaction or db handle.
  - balance: the frozen balance row, carrying its full primary key.
  - whole-units: units to add to `:credit`.
  - carry: the new `:credit-carry`."
  [txn balance whole-units carry]
  (core/accrue txn balance whole-units carry))
