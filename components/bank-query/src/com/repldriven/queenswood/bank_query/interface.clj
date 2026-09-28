(ns com.repldriven.queenswood.bank-query.interface
  "Read-side (query) surface for banks: load one flat, list rich, and
  the rich single-bank view (party + accounts with
  balances + client-id). This is the only bank brick `bank-api` (and
  other readers) may require — it exposes no writes. Bank provisioning
  lives in `bank-bank` (commands), reached over the bus."
  (:require
    [com.repldriven.queenswood.bank-query.core :as core]
    [com.repldriven.queenswood.bank-query.store :as store]))

(defn get-bank
  "Load a flat bank map by id. Returns the bank or a
  `:bank/not-found` rejection anomaly.

  Args:
  - txn: FDB transaction or db handle.
  - bank-id: bank id."
  [txn bank-id]
  (store/get-bank txn bank-id))

(defn find-bank
  "Load a flat bank map by id. Returns the bank, nil where no bank has
  that id, or an anomaly.

  Args:
  - txn: FDB transaction or db handle.
  - bank-id: bank id."
  [txn bank-id]
  (store/find-bank txn bank-id))

(defn get-bank-view
  "Load a bank by id enriched with its party, its accounts (with
  balances and GL codes), and `:client-id`. Returns the rich bank map,
  a `:bank/not-found` rejection, or an anomaly.

  Args:
  - txn: FDB transaction or db handle.
  - bank-id: bank id."
  [txn bank-id]
  (core/get-bank-view txn bank-id))

(defn get-banks
  "One page of banks, each enriched with its party and accounts (with
  balances). Returns `{:banks [...] :before id|nil :after id|nil}`, the
  cursors set only where banks lie on that side of the page, or an
  anomaly.

  Args:
  - txn: FDB transaction or db handle.
  - opts (optional): map; `:after`, `:before`, `:limit` (default 100)
    and `:order` (default `:desc`)."
  ([txn] (core/get-banks txn))
  ([txn opts] (core/get-banks txn opts)))
