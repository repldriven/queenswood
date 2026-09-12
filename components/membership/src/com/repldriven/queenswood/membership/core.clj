(ns com.repldriven.queenswood.membership.core
  (:require
    [com.repldriven.queenswood.membership.domain :as domain]
    [com.repldriven.queenswood.membership.store :as store]

    [com.repldriven.mono.error.interface :refer [let-nom>]]
    [com.repldriven.mono.utility.interface :as utility]))

(defn new-membership
  [txn {:keys [user-id bank-id role]}]
  (store/transact
   txn
   (fn [txn]
     (let [membership (domain/new-membership
                       {:user-id user-id
                        :bank-id bank-id
                        :role role}
                       (utility/now))]
       (let-nom> [_ (store/create txn membership)]
         membership)))
   :membership/new
   "Failed to create membership"))

(defn list-by-user
  [txn user-id]
  (store/list-by-user txn user-id))

(defn list-by-bank
  [txn bank-id]
  (store/list-by-bank txn bank-id))

(defn find-by-id
  [txn membership-id]
  (let-nom> [membership (store/get-membership txn membership-id)]
    (domain/ensure-found membership membership-id)))
