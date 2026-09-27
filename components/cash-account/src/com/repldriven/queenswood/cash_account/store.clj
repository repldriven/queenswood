(ns com.repldriven.queenswood.cash-account.store
  (:require
    [com.repldriven.queenswood.cash-account.changelog :as changelog]

    [com.repldriven.queenswood.fdb.interface :as fdb]
    [com.repldriven.queenswood.schema.interface :as schema]

    [com.repldriven.mono.error.interface :refer [let-nom>]]))

;; must match cash-account-query.store/store-name — same FDB store
(def ^:private store-name "cash-accounts")

(def transact fdb/transact)

(def uniqueness-violation? fdb/uniqueness-violation?)

(defn save-account
  [txn account changelog]
  (fdb/transact
   txn
   (fn [txn]
     (let [store (fdb/open txn store-name)]
       (let-nom>
         [_ (fdb/save-record store (schema/CashAccount->java account))
          entry (changelog/account-changed
                 (assoc changelog
                        :bank-id (:bank-id account)
                        :updated-at (:updated-at account)))
          _ (fdb/write-changelog txn
                                 store-name
                                 (:account-id account)
                                 entry)]
         nil)))
   :cash-account/save
   "Failed to save account"))
