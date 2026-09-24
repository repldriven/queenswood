(ns com.repldriven.queenswood.api.bank.queries
  (:require
    [com.repldriven.queenswood.api.access.names :as names]
    [com.repldriven.queenswood.api.cursor :as cursor]
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
  "The 200 body for one page of banks, `found` as `get-banks` returns it,
  each bank given the owners `owners-of` names for its id."
  [page found owners-of]
  (let [result (let-nom> [{:keys [banks]} found]
                 (with-owners banks owners-of))]
    (if (error/anomaly? result)
      (errors/anomaly->response result)
      {:status 200 :body (cursor/page-body "/v1/banks" page result found)})))

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

(defn get-bank
  [request]
  (let [{:keys [auth parameters record-db record-store]} request
        {:keys [bank-id]} (:path parameters)
        config {:record-db record-db :record-store record-store}]
    (if-not (or (= bank-id (:bank-id auth)) (contains? (:roles auth) :admin))
      (errors/forbidden-response
       "Token is not this bank's; retrieve only your own bank")
      (let [result (let-nom>
                     [bank (banks/get-bank-view config bank-id)
                      {:keys [list-active lookup]} (owner-lookups config [bank])
                      owners (names/owners list-active lookup bank-id)]
                     (assoc bank :owners owners))]
        (if (error/anomaly? result)
          (errors/anomaly->response result)
          {:status 200 :body result})))))

(defn list-banks
  [request]
  (let [{:keys [record-db record-store parameters]} request
        {:keys [page]} (:query parameters)
        config {:record-db record-db :record-store record-store}
        found (banks/get-banks config (cursor/page-opts page))
        lookups (if (error/anomaly? found)
                  found
                  (owner-lookups config (:banks found)))]
    (if (error/anomaly? lookups)
      (errors/anomaly->response lookups)
      (let [{:keys [list-active lookup]} lookups]
        (banks-response page
                        found
                        (fn [bank-id]
                          (names/owners list-active lookup bank-id)))))))
