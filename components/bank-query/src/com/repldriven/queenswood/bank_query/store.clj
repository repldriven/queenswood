(ns com.repldriven.queenswood.bank-query.store
  (:require
    [com.repldriven.queenswood.fdb.interface :as fdb]
    [com.repldriven.queenswood.schema.interface :as schema]

    [com.repldriven.mono.error.interface :as error]))

;; must match bank-bank.store/store-name — same FDB store
(def ^:private store-name "banks")

(def transact fdb/transact)

(defn- ->bank
  "Translate a Bank protobuf record to a plain map, `:providers` a vector
  of `{:kind :provider}` maps."
  [record]
  (let [bank (schema/pb->Bank record)]
    (update bank :providers (partial mapv (fn [p] (into {} p))))))

(defn get-bank
  [txn bank-id]
  (fdb/transact txn
                (fn [txn]
                  (if-let [record (fdb/load-record (fdb/open txn store-name)
                                                   bank-id)]
                    (->bank record)
                    (error/reject :bank/not-found
                                  {:message "Bank not found"
                                   :bank-id bank-id})))
                :bank/get
                "Failed to load bank"))

(defn find-bank
  [txn bank-id]
  (fdb/transact txn
                (fn [txn]
                  (some-> (fdb/load-record (fdb/open txn store-name) bank-id)
                          ->bank))
                :bank/get
                "Failed to load bank"))

(defn get-banks
  ([txn]
   (get-banks txn nil))
  ([txn opts]
   (fdb/transact
    txn
    (fn [txn]
      (let [{:keys [after before limit order] :or {limit 100 order :desc}}
            opts
            result (fdb/scan-records (fdb/open txn store-name)
                                     {:after after
                                      :before before
                                      :limit limit
                                      :order order})]
        {:banks (mapv ->bank (:records result))
         :before (:before result)
         :after (:after result)}))
    :bank/list
    "Failed to list banks")))
