(ns com.repldriven.queenswood.balance-domain.interface
  "Pure balance arithmetic shared by the read and write sides of
  balances. `posted-balance` / `available-balance` reduce a set of
  balance buckets to a credit-positive total (available additionally
  filters to the spendable buckets); `trial-balance` aggregates
  account-level posted nets into a per-currency debit/credit block.
  No FDB, no policy — just money maths, so both `bank-balance-query`
  (read enrichment) and `bank-balance` (the apply-legs available-limit
  check) can depend on it without a cycle."
  (:require
    [com.repldriven.queenswood.balance-domain.domain :as domain]))

(defn posted-balance
  "Credit-positive total of the posted (settled) buckets in `balances`,
  as `{:value :currency}`.

  Args:
  - balances: collection of balance maps.
  - currency: ISO 4217 currency string stamped on the result."
  [balances currency]
  (domain/posted-balance balances currency))

(defn available-balance
  "Credit-positive total of the spendable buckets in `balances` (posted
  plus pending-outgoing reservations, excluding GL), as
  `{:value :currency}`.

  Args:
  - balances: collection of balance maps.
  - currency: ISO 4217 currency string stamped on the result."
  [balances currency]
  (domain/available-balance balances currency))

(defn derived?
  "Whether `balance` is the sum of its legs rather than what its row
  holds: a cash account's default bucket, whose rows a posting no longer
  writes. Every other bucket, and every general-ledger account's, is the
  row as stored. ADR-0042.

  Args:
  - balance: a balance map."
  [balance]
  (domain/derived? balance))

(defn lowers-available?
  "Whether one account's `legs` together lower its available balance:
  they take more from its posted and pending-outgoing default buckets
  than they add. Only such a posting needs its account's sums read
  serializably.

  Args:
  - legs: one account's legs."
  [legs]
  (domain/lowers-available? legs))

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
