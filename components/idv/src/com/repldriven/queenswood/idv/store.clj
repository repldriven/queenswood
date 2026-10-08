(ns com.repldriven.queenswood.idv.store
  (:require
    [com.repldriven.queenswood.idv.changelog :as changelog]

    [com.repldriven.queenswood.fdb.interface :as fdb]
    [com.repldriven.queenswood.schema.interface :as schema]

    [com.repldriven.mono.error.interface :refer [let-nom>]]))

(def ^:private store-name "idvs")

(def ^:private sessions-store-name "idv-sessions")

(def transact fdb/transact)
(def uniqueness-violation? fdb/uniqueness-violation?)

(defn save-idv
  "Save `idv`, and when `changelog` is given, the status transition it
  describes to the idvs changelog."
  [txn idv changelog]
  (fdb/transact
   txn
   (fn [txn]
     (let [store (fdb/open txn store-name)]
       (let-nom>
         [_ (fdb/save-record store (schema/Idv->java idv))
          _ (when changelog
              (let-nom> [entry (changelog/status-changed
                                (assoc changelog
                                       :bank-id (:bank-id idv)
                                       :party-id (:party-id idv)))]
                (fdb/write-changelog txn
                                     store-name
                                     (:verification-id idv)
                                     entry)))]
         idv)))
   :idv/save
   "Failed to save IDV"))

(defn save-session
  [txn session status-before]
  (fdb/transact
   txn
   (fn [txn]
     (let [store (fdb/open txn sessions-store-name)]
       (let-nom>
         [_ (fdb/save-record store (schema/IdvSession->java session))
          entry (changelog/session-status-changed
                 {:bank-id (:bank-id session)
                  :session-id (:session-id session)
                  :verification-id (:verification-id session)
                  :party-id (:party-id session)
                  :status-before status-before
                  :status-after (:status session)})
          _ (fdb/write-changelog txn
                                 sessions-store-name
                                 (:session-id session)
                                 entry)]
         session)))
   :idv/save-session
   "Failed to save IDV session"))
