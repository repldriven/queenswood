(ns com.repldriven.queenswood.interest.interface
  "Interest accrual and capitalisation for customer cash accounts.
  Iterates an organisation's customer accounts on a given as-of date
  and posts the resulting accrual or capitalisation transactions.
  Returns a per-call summary map or an anomaly."
  (:require
    [com.repldriven.queenswood.interest.core :as core]))

(defn accrue-day
  "Post one day's interest accrual for every customer account in the
  organisation as of the given date. The cadence is the scheduler
  job's; a day is the unit this computes.

  Args:
  - config: FDB handle plus product/balance/transaction interfaces.
  - data: map with :bank-id and :as-of-date (YYYYMMDD int).

  Returns `{:bank-id :as-of-date :accounts-processed}` or
  an anomaly."
  [config data]
  (core/accrue-day config data))

(defn capitalize-accrued
  "Sweep whatever interest has accrued into the customer's
  posted/default balance, for every customer account in the
  organisation as of the given date. Sweeps the balance it finds — the
  cadence is the scheduler job's, and may be daily, monthly or yearly.

  Args:
  - config: FDB handle plus product/balance/transaction interfaces.
  - data: map with :bank-id and :as-of-date (YYYYMMDD int).

  Returns `{:bank-id :as-of-date :accounts-processed}` or
  an anomaly."
  [config data]
  (core/capitalize-accrued config data))

(defn run-progress
  "How far one run got: how many accounts were enumerated into scope,
  and how many are done, failed, or still pending. Pending rows are the
  outstanding work — re-running the pass picks them up.

  Counts read at SNAPSHOT, so polling progress never conflicts with the
  postings it is observing.

  Args:
  - config: FDB handle.
  - bank-id: the bank the run belongs to.
  - as-of-date: business day (YYYYMMDD int).
  - kind: `:accrue` or `:capitalize`.

  Returns `{:scope :done :failed :pending :run-state}` or an anomaly.
  `:run-state` is nil until the run record is written, which happens
  once enumeration finishes.

  Counts only. What a run accrued is per currency, and the ledger
  entries it posts at close are where that figure is auditable."
  [config bank-id as-of-date kind]
  (core/run-progress config bank-id as-of-date kind))
