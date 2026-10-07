(ns com.repldriven.queenswood.party.store
  (:require
    [com.repldriven.queenswood.party.changelog :as changelog]

    [com.repldriven.queenswood.fdb.interface :as fdb]
    [com.repldriven.queenswood.schema.interface :as schema]

    [com.repldriven.mono.error.interface :refer [let-nom>]]))

;; must match bank-party-query.store/store-name — same FDB store
(def ^:private store-name "parties")

;; Nothing writes national identifiers (ADR-0045); the store stays until
;; every instance has deleted its records.
(def ^:private national-identifiers-store-name "party-national-identifiers")

(def transact fdb/transact)
(def uniqueness-violation? fdb/uniqueness-violation?)

(defn save-party
  [txn party changelog]
  (fdb/transact
   txn
   (fn [txn]
     (let [store (fdb/open txn store-name)]
       (let-nom>
         [_ (fdb/save-record store (schema/Party->java party))
          entry (changelog/status-changed
                 (assoc changelog
                        :bank-id
                        (:bank-id party)))
          _ (fdb/write-changelog txn
                                 store-name
                                 (:party-id party)
                                 entry)]
         (schema/Party->pb party))))
   :party/save
   "Failed to save party"))

(defn delete-national-identifiers
  [config]
  (fdb/rewrite-store config
                     national-identifiers-store-name
                     (fn [_txn _bytes] :delete)
                     {}))
