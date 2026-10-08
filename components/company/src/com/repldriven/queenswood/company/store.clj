(ns com.repldriven.queenswood.company.store
  (:require
    [com.repldriven.queenswood.fdb.interface :as fdb]
    [com.repldriven.queenswood.schema.interface :as schema]

    [com.repldriven.mono.utility.interface :as utility]))

(def ^:private store-name "companies")

(defn- load-company
  [store registry company-number]
  (some-> (fdb/load-record store
                           (schema/company-registry->int registry)
                           company-number)
          schema/pb->Company))

(defn save-company
  [txn-or-config company]
  (fdb/transact
   txn-or-config
   (fn [txn]
     (let [store (fdb/open txn store-name)
           {:keys [registry company-number]} company
           existing (load-company store registry company-number)
           now (utility/now)
           saved (assoc company
                        :created-at (or (:created-at existing) now)
                        :updated-at now)]
       (fdb/save-record store (schema/Company->java saved))
       saved))
   :company/save
   "Failed to save company"))

(defn get-company
  [txn-or-config registry company-number]
  (fdb/transact
   txn-or-config
   (fn [txn]
     (load-company (fdb/open txn store-name) registry company-number))
   :company/get
   "Failed to load company"))
