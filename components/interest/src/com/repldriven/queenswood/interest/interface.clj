(ns com.repldriven.queenswood.interest.interface
  "Interest accrual and capitalisation for customer cash accounts.
  Iterates an organisation's customer accounts on a given as-of date
  and posts the resulting accrual or capitalisation transactions, and
  settles one account's accrued interest when it closes. Returns a
  per-call summary map or an anomaly."
  (:require
    [com.repldriven.queenswood.interest.core :as core]
    [com.repldriven.queenswood.interest.settle :as settle]))

(defn accrue-day
  "Post one day's interest accrual for every customer account in the
  organisation as of the given date. The cadence is the scheduler
  job's; a day is the unit this computes.

  Args:
  - config: FDB handle plus product/balance/transaction interfaces,
    with `:interest-chunks-in-flight`, how many chunks of a hundred
    accounts it posts at once, one where absent.
  - data: map with :bank-id and :as-of-date (YYYYMMDD int).

  Returns `{:bank-id :as-of-date :accounts-processed}`,
  `:interest/run-incomplete` while any account failed to post, or
  another anomaly. A second call for the date posts the failed accounts
  and closes the run."
  [config data]
  (core/accrue-day config data))

(defn capitalize-accrued
  "Sweep whatever interest has accrued into the customer's
  posted/default balance, for every customer account in the
  organisation as of the given date. Sweeps the balance it finds — the
  cadence is the scheduler job's, and may be daily, monthly or yearly.

  Args:
  - config: FDB handle plus product/balance/transaction interfaces,
    with `:interest-chunks-in-flight`, how many chunks of a hundred
    accounts it posts at once, one where absent.
  - data: map with :bank-id and :as-of-date (YYYYMMDD int).

  Returns `{:bank-id :as-of-date :accounts-processed}`,
  `:interest/run-incomplete` while any account failed to post, or
  another anomaly. A second call for the date posts the failed accounts
  and closes the run."
  [config data]
  (core/capitalize-accrued config data))

(defn run-progress
  "How far one run got: how many accounts were enumerated into scope,
  and how many are done, failed, or still pending. Failed and pending
  rows are the outstanding work — re-running the pass picks them up.

  Counts read at SNAPSHOT, so polling progress never conflicts with the
  postings it is observing.

  Args:
  - config: FDB handle.
  - bank-id: the bank the run belongs to.
  - as-of-date: business day (YYYYMMDD int).
  - kind: `:accrue` or `:capitalize`.

  Returns `{:scope :done :failed :pending :run-status}` or an anomaly.
  `:run-status` is nil until the run record is written, which happens
  once enumeration finishes.

  Counts only. What a run accrued is in the transactions its chunks
  record, against interest expense."
  [config bank-id as-of-date kind]
  (core/run-progress config bank-id as-of-date kind))

(defn accrual-carry
  "The sub-unit carry, in millionths of a minor unit, that an account's
  next accrual opens with: what its earlier accruals left over. Zero for
  an account none has accrued.

  Args:
  - config: FDB handle or open transaction.
  - bank-id: the account's bank.
  - account-id: the cash account."
  [config bank-id account-id]
  (core/accrual-carry config bank-id account-id))

(defn settle-accrued
  "Settle a closing account's accrued interest by `:interest`, the
  closure's interest term from its version's closure terms, inside the
  close's own transaction, at the version's rates: what is paid
  moves from its interest-accrued bucket to its default one, as
  capitalisation moves it, and what is forfeited leaves the bucket
  against interest expense in an `interest-forfeiture` transaction, so
  interest payable still balances. `forfeit-days` forfeits that many
  days' interest on the account's balance at the day's rates, never
  more than has accrued. The sub-unit carry is forfeited whatever the
  term, by being left behind.

  Args:
  - txn: an open FDB transaction.
  - data: map with :account, the cash account closing, :interest, a
    `ClosureInterest` `{:kind {:pay {}}}`, `{:kind {:forfeit {}}}` or
    `{:kind {:forfeit-days {:days n}}}`, :business-day, the close's epoch
    day, and :policies, the bank's effective policies.

  Returns `{:paid :forfeited}` in minor units, both zero where nothing
  has accrued, or an anomaly."
  [txn data]
  (settle/settle-accrued txn data))
