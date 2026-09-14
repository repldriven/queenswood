(ns com.repldriven.queenswood.api.bank.queries
  (:require
    [com.repldriven.queenswood.api.access.names :as names]
    [com.repldriven.queenswood.api.errors :as errors]

    [com.repldriven.queenswood.bank-query.interface :as banks]
    [com.repldriven.queenswood.membership.interface :as memberships]
    [com.repldriven.queenswood.user.interface :as users]

    [com.repldriven.mono.error.interface :as error :refer [let-nom>]]))

(defn- with-owners
  [found owners-of]
  (reduce (fn [enriched bank]
            (let [owners (owners-of (:bank-id bank))]
              (if (error/anomaly? owners)
                (reduced owners)
                (conj enriched (assoc bank :owners owners)))))
          []
          found))

(defn banks-response
  [found owners-of]
  (let [result (let-nom> [listed found]
                 (with-owners listed owners-of))]
    (if (error/anomaly? result)
      (errors/anomaly->response result)
      {:status 200
       :body {:banks result}})))

(defn list-banks
  [request]
  (let [{:keys [record-db record-store]} request
        config {:record-db record-db :record-store record-store}]
    (banks-response (banks/get-banks config)
                    (fn [bank-id]
                      (names/owners (fn [id]
                                      (memberships/list-active-by-bank config
                                                                       id))
                                    (fn [id] (users/find-by-id config id))
                                    bank-id)))))
