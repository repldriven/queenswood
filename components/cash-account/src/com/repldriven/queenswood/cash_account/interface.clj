(ns com.repldriven.queenswood.cash-account.interface
  "Cash account write side: open, close, suspend, resume, and
  rotate-address, and migrate-product for banks. Open allocates payment addresses,
  derives account-type from the party, validates the chosen product
  version, and seeds the product's balance buckets. Open and close
  transitions are driven by the changelog relay and this brick's
  event-processor; `seed-opened-account` and `seed-closed-account` are
  admin/test shortcuts that bypass that leg. Suspend, resume, and
  rotate-address are direct, single-phase flips — no second leg. Rotate-address stays on
  `:cash-account-status-opened`, replacing the account's payment
  addresses with a freshly allocated set and retiring the old ones
  on-record.

  Account numbers are retired forever on close or rotation, never
  recycled — the fountain behind `store/allocate-payment-address` is
  a monotonic counter that structurally can't re-issue a number.
  Closing an account doesn't need to inform the fountain; there's
  nothing to release.

  Reads live in `bank-cash-account-query`; this brick reuses them inside
  its own transactions. `bank-api` requires the query brick, not this
  one — state changes reach the processor as commands over the bus."
  (:require
    [com.repldriven.queenswood.cash-account.system]

    [com.repldriven.queenswood.cash-account.core :as core]
    [com.repldriven.queenswood.cash-account.domain :as domain]
    [com.repldriven.queenswood.cash-account.store :as store]

    [com.repldriven.queenswood.cash-account-query.interface :as q]

    [com.repldriven.mono.error.interface :refer [let-nom>]]))

(defn new-account
  "Open a cash account, seeding the product's balance buckets.
  Returns the account map (`:cash-account-status-opening`) or an
  anomaly.

  Args:
  - txn: FDB transaction or db handle.
  - data: map with `:bank-id`, `:party-id`, `:product-id`,
    `:currency`, `:name`.
  - opts (optional): map; `:policies` overrides policy resolution
    for the capability and limit checks."
  ([txn data]
   (core/open-account txn data))
  ([txn data opts]
   (core/open-account txn data opts)))

(defn close-account
  "Close an account. Returns the updated account
  (`:cash-account-status-closing`) or an anomaly.

  Args:
  - txn: FDB transaction or db handle.
  - data: map with `:bank-id` and `:account-id`.
  - opts (optional): map; `:policies` overrides policy resolution."
  ([txn data]
   (core/close-account txn data))
  ([txn data opts]
   (core/close-account txn data opts)))

(defn suspend-account
  "Suspend an opened account. Direct single-phase flip, no second
  leg. Returns the updated account (`:cash-account-status-suspended`)
  or an anomaly.

  Args:
  - txn: FDB transaction or db handle.
  - data: map with `:bank-id` and `:account-id`.
  - opts (optional): map; `:policies` overrides policy resolution."
  ([txn data]
   (core/suspend-account txn data))
  ([txn data opts]
   (core/suspend-account txn data opts)))

(defn resume-account
  "Resume a suspended account. Direct single-phase flip, no second
  leg. Returns the updated account (`:cash-account-status-opened`)
  or an anomaly.

  Args:
  - txn: FDB transaction or db handle.
  - data: map with `:bank-id` and `:account-id`.
  - opts (optional): map; `:policies` overrides policy resolution."
  ([txn data]
   (core/resume-account txn data))
  ([txn data opts]
   (core/resume-account txn data opts)))

(defn rotate-address
  "Rotate an opened account onto a freshly allocated set of payment
  addresses, permanently retiring the old ones on-record. Direct
  single-phase flip, no second leg. Returns the updated account
  (`:cash-account-status-opened`) or an anomaly.

  A rotation stamps its `:idempotency-key` onto the account, and a
  command repeating the key that last rotated it returns the account
  untouched — no address is allocated and nothing is written. That is
  what a retry after a lost reply gets, rather than a second set of
  addresses and a second retirement.

  Args:
  - txn: FDB transaction or db handle.
  - data: map with `:bank-id`, `:account-id` and, on the command
    path, the envelope's `:idempotency-key`.
  - opts (optional): map; `:policies` overrides policy resolution."
  ([txn data]
   (core/rotate-address txn data))
  ([txn data opts]
   (core/rotate-address txn data opts)))

(defn migrate-product
  "Repin an opened account to another product version, leaving its
  balances, payment addresses and account number alone — the same
  account on different terms. Direct single-phase flip, no second leg.
  Returns the updated account or an anomaly.

  Whether the target is a sensible destination for this account —
  published, the same product type, a currency it may hold — is settled
  by whoever assembled the cohort. `cash-account-migration` does that
  per account and calls `migrate-account` for the ones that pass.

  Args:
  - txn: FDB transaction or db handle.
  - data: map with `:bank-id`, `:account-id`, `:target-product-id`
    and `:target-version-id`.
  - opts (optional): map; `:policies` overrides policy resolution."
  ([txn data]
   (core/migrate-product txn data))
  ([txn data opts]
   (core/migrate-product txn data opts)))

(defn migrate-account
  "Repin an account the caller already holds, joining the caller's
  transaction so a chunk of moves commits together. Returns the updated
  account or an anomaly.

  The batch door onto `migrate-product`'s work. A pass over a cohort has
  already streamed each account, has one target version for all of them,
  and resolves policy once for the run; passing those in is what keeps a
  million-account migration from becoming a million round-trips.

  Args:
  - txn: an open FDB transaction — this joins it rather than opening
    one, so a caller can commit the move and its own bookkeeping
    together.
  - account: the account map, as streamed.
  - target-version: the product version to repin onto.
  - policies: effective policies for the run."
  [txn account target-version policies]
  (core/migrate-account txn account target-version policies))

(defn seed-opened-account
  "Test/admin shortcut: flip an account from
  `:cash-account-status-opening` to `:cash-account-status-opened`
  by writing the transition straight to the store, bypassing the
  relay and event-processor that run the transition in production.
  Same spirit as `bank-party/seed-active-party`. Returns the opened account or
  an anomaly.

  Args:
  - txn: FDB transaction or db handle.
  - bank-id: owning bank id.
  - account-id: account id."
  [txn bank-id account-id]
  (let-nom>
    [account (q/get-account txn bank-id account-id)
     opened (domain/opened-account account)
     saved (store/save-account txn
                               opened
                               {:account-id account-id
                                :status-before (:account-status account)
                                :status-after (:account-status opened)
                                :change-kind
                                :cash-account-change-kind-open})]
    saved))

(defn seed-closed-account
  "Test/admin shortcut: flip an account from
  `:cash-account-status-closing` to `:cash-account-status-closed`,
  bypassing the relay and event-processor. Counterpart to
  `seed-opened-account`. Returns the closed account or an anomaly.

  Args:
  - txn: FDB transaction or db handle.
  - bank-id: owning bank id.
  - account-id: account id."
  [txn bank-id account-id]
  (let-nom>
    [account (q/get-account txn bank-id account-id)
     closed (domain/closed-account account)
     saved (store/save-account txn
                               closed
                               {:account-id account-id
                                :status-before (:account-status account)
                                :status-after (:account-status closed)
                                :change-kind
                                :cash-account-change-kind-close})]
    saved))
