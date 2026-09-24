(ns com.repldriven.queenswood.bank-query.store
  (:require
    [com.repldriven.queenswood.fdb.interface :as fdb]
    [com.repldriven.queenswood.schema.interface :as schema]

    [com.repldriven.mono.error.interface :as error]
    [com.repldriven.mono.utility.interface :as utility]))

;; must match bank-bank.store/store-name — same FDB store
(def ^:private store-name "banks")

(def transact fdb/transact)

(defn- ->bank
  "Translate a Bank protobuf record to a plain map. The protojure
  record carries `:company-binding nil` for admin-provisioned banks and
  `:tier nil` for a bank with no tier bound; both keys must be absent
  so API response coercion (optional key, no nil) passes."
  [record]
  (let [{:keys [company-binding tier] :as bank} (schema/pb->Bank record)]
    (-> (into {} bank)
        (dissoc :company-binding :tier)
        (utility/assoc-some :company-binding
                            (some->> company-binding
                                     (into {}))
                            :tier
                            tier))))

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

(defn get-bank-by-sort-code
  [txn sort-code]
  (fdb/transact txn
                (fn [txn]
                  (some-> (fdb/query-record (fdb/open txn store-name)
                                            "Bank"
                                            "sort_code"
                                            sort-code
                                            {:index "Bank_by_sort_code"})
                          ->bank))
                :bank/get-by-sort-code
                "Failed to get bank by sort code"))

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
