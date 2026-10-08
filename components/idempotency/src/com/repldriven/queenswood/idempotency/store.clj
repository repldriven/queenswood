(ns com.repldriven.queenswood.idempotency.store
  (:require
    [com.repldriven.queenswood.fdb.interface :as fdb]
    [com.repldriven.queenswood.schema.interface :as schema]))

(def ^:private store-name "idempotency")

(def transact fdb/transact)

(defn save
  "Persist an idempotency cache entry. `entry` is the map shape of
  the `Idempotency` proto: `:principal-id :operation :idempotency-key
  :status :fingerprint :response :expires-at :completed-at :created-at`,
  `:response` a map of `:status`, `:headers` and `:body`.

  `txn-or-config` accepts either an open `fdb.record/Txn` (composes
  inside an outer transaction) or a `{:record-db :record-store}`
  config map (opens its own transaction)."
  [txn-or-config entry]
  (fdb/transact
   txn-or-config
   (fn [txn]
     (fdb/save-record (fdb/open txn store-name)
                      (schema/Idempotency->java entry)))
   :idempotency/save
   "Failed to save idempotency entry"))

(defn lookup
  "Load the cached entry for [principal-id operation idempotency-key],
  or nil if no entry exists. Returns an anomaly on FDB error.

  `txn-or-config` — see `save`."
  [txn-or-config principal-id operation idempotency-key]
  (fdb/transact
   txn-or-config
   (fn [txn]
     (some-> (fdb/load-record (fdb/open txn store-name)
                              principal-id
                              operation
                              idempotency-key)
             schema/pb->Idempotency))
   :idempotency/lookup
   "Failed to load idempotency entry"))

(defn delete
  "Remove the entry for [principal-id operation idempotency-key]. Used
  to release a `pending` claim on non-cacheable (5xx) responses or
  when the handler threw, so the caller can retry immediately.

  `txn-or-config` — see `save`."
  [txn-or-config principal-id operation idempotency-key]
  (fdb/transact
   txn-or-config
   (fn [txn]
     (fdb/delete-record (fdb/open txn store-name)
                        principal-id
                        operation
                        idempotency-key))
   :idempotency/delete
   "Failed to delete idempotency entry"))
