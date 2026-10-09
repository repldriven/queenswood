(ns com.repldriven.queenswood.party-query.core
  (:require
    [com.repldriven.queenswood.party-query.store :as store]

    [com.repldriven.mono.error.interface :as error :refer [let-nom>]]))

(defn get-party
  [txn bank-id party-id]
  (let-nom> [party (store/get-party txn bank-id party-id)]
    (or party
        (error/reject :party/not-found
                      {:message "Party not found"
                       :bank-id bank-id
                       :party-id party-id}))))

(defn get-party-detail
  [txn bank-id party-id {:keys [legal-name]}]
  (let-nom> [party (get-party txn bank-id party-id)]
    (cond-> party
            (not legal-name)
            (dissoc :legal-name))))
