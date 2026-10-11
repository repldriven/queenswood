(ns com.repldriven.queenswood.api.access-log.queries
  "The bank's access log, newest first, each call's caller named as the
  access routes name one."
  (:require
    [com.repldriven.queenswood.api.access.names :as names]
    [com.repldriven.queenswood.api.cursor :as cursor]
    [com.repldriven.queenswood.api.errors :as errors]

    [com.repldriven.queenswood.access-log.interface :as access-log]
    [com.repldriven.queenswood.bank-query.interface :as banks]
    [com.repldriven.queenswood.user.interface :as users]

    [com.repldriven.mono.error.interface :as error :refer [let-nom>]]))

(defn- config
  [{:keys [record-db record-store]}]
  {:record-db record-db :record-store record-store})

(defn- lookup
  [txn]
  (fn [user-id] (users/find-by-id txn user-id)))

(defn- named-access-entries
  [txn entries]
  (let-nom> [names (names/user-names (lookup txn)
                                     (names/actor-ids (map :requested-by
                                                           entries)))]
    (mapv (fn [entry]
            (update entry :requested-by names/->actor names))
          entries)))

(defn list-access-entries
  [request]
  (let [{:keys [auth parameters]} request
        {:keys [bank-id]} auth
        {:keys [page ref]} (:query parameters)
        txn (config request)
        result (let-nom>
                 [_ (banks/get-bank txn bank-id)
                  found (access-log/list-by-bank
                         txn
                         bank-id
                         (assoc (cursor/page-opts page) :ref ref))
                  named (named-access-entries txn (:access-entries found))]
                 (cursor/page-body (cursor/request-path request)
                                   page
                                   named
                                   found))]
    (if (error/anomaly? result)
      (errors/anomaly->response result)
      {:status 200 :body result})))
