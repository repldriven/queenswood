(ns com.repldriven.queenswood.balance.interface
  "Balance write side: create an account's balance buckets, and apply
  transaction legs to them (with policy-gated capability and limit
  checks). Buckets are keyed by `(account-id, balance-type,
  balance-status)`.

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
    `:product-type`, `:balance-type`, `:balance-status`.
  - opts (optional): map; `:policies` overrides policy resolution."
  ([txn bank-id data]
   (core/new-balances txn bank-id data))
  ([txn bank-id data opts]
   (core/new-balances txn bank-id data opts)))

(defn accept-legs
  "Ask each cash account a posting's `legs` name to accept them: check
  the `:balance-action-apply` capability on each leg's bucket and the
  computed `:available` limit per account. Takes the whole posting and
  acts on the legs that carry a cash account's `:product-type`; a
  general-ledger leg is a ledger account's, whose balance is read from
  the journal, and is passed over. Returns nil on success
  or the first refusal, an anomaly. `transaction-type` scopes which
  limits fire via the limit's `transaction-type` filter.

  A bucket is the sum of its legs, so no row is rewritten: a leg must
  already be recorded in this transaction, which is how its sum moves,
  and the check reads the sum less the posting's own legs as the balance
  before it. A bucket a leg reaches first is opened, its row saved at
  zero. An account's sums are read serializably only where a limit in
  force bounds the way the posting moves its available balance, a floor
  for a posting that lowers it or a cap for one that raises it;
  otherwise at snapshot, so a credit to an account with no cap conflicts
  with nothing else posting to it. ADR-0042.

  Args:
  - txn: FDB transaction or db handle.
  - bank-id: owning bank id, which keys the balances this reads and
    opens a bucket a leg reaches first.
  - legs: a posting's recorded legs; each carries `:account-id`,
    `:product-type`, `:balance-type`, `:balance-status`, `:side` and
    `:amount`.
  - transaction-type: transaction-type keyword (e.g.
    `:transaction-type-internal-transfer`).
  - opts (optional): map; `:policies` overrides policy resolution."
  ([txn bank-id legs transaction-type]
   (core/accept-legs txn bank-id legs transaction-type))
  ([txn bank-id legs transaction-type opts]
   (core/accept-legs txn bank-id legs transaction-type opts)))
