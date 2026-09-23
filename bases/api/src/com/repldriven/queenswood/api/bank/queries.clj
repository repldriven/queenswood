(ns com.repldriven.queenswood.api.bank.queries
  (:require
    [com.repldriven.queenswood.api.access.names :as names]
    [com.repldriven.queenswood.api.errors :as errors]

    [com.repldriven.queenswood.bank-query.interface :as banks]
    [com.repldriven.queenswood.membership-query.interface :as memberships]
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

(defn- owner-lookups
  "The two lookups `names/owners` takes, backed by one read of every
  listed bank's active memberships and one of their owners' users,
  rather than a read per bank and per owner. Returns
  `{:list-active f :lookup f}` or an anomaly."
  [config found]
  (let-nom> [active (memberships/list-active-by-banks config
                                                      (map :bank-id found))
             users (users/find-by-ids config
                                      (into #{}
                                            (comp cat
                                                  (filter #(= :role-owner
                                                              (:role %)))
                                                  (map :user-id))
                                            (vals active)))]
    {:list-active (fn [bank-id] (get active bank-id []))
     :lookup (fn [user-id] (get users user-id))}))

(defn list-banks
  [request]
  (let [{:keys [record-db record-store]} request
        config {:record-db record-db :record-store record-store}
        found (banks/get-banks config)
        lookups (if (error/anomaly? found) found (owner-lookups config found))]
    (if (error/anomaly? lookups)
      (errors/anomaly->response lookups)
      (let [{:keys [list-active lookup]} lookups]
        (banks-response found
                        (fn [bank-id]
                          (names/owners list-active lookup bank-id)))))))
