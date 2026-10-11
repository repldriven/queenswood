(ns com.repldriven.queenswood.api.audit.queries
  "The bank's audit log, newest first, each act's actor named as the
  access routes name one."
  (:require
    [com.repldriven.queenswood.api.access.names :as names]
    [com.repldriven.queenswood.api.cursor :as cursor]
    [com.repldriven.queenswood.api.errors :as errors]

    [com.repldriven.queenswood.audit.interface :as audit]
    [com.repldriven.queenswood.bank-query.interface :as banks]
    [com.repldriven.queenswood.user.interface :as users]

    [com.repldriven.mono.error.interface :as error :refer [let-nom>]]))

(defn- config
  [{:keys [record-db record-store]}]
  {:record-db record-db :record-store record-store})

(defn- lookup
  [txn]
  (fn [user-id] (users/find-by-id txn user-id)))

(defn- ->audit-entry
  "An audit entry as the bank's audit log shows it, its actor named."
  [entry names]
  (-> (select-keys entry
                   [:audit-entry-id :bank-id :kind :subject-kind :subject-id
                    :performed-at :request-id :changeset])
      (assoc :performed-by (names/->actor (:performed-by entry) names))))

(defn- named-audit-entries
  [txn entries]
  (let-nom> [names (names/user-names (lookup txn)
                                     (names/actor-ids (map :performed-by
                                                           entries)))]
    (mapv (fn [entry] (->audit-entry entry names)) entries)))

(defn list-audit-entries
  [request]
  (let [{:keys [auth parameters]} request
        {:keys [bank-id]} auth
        {:keys [page kind performed-by-kind request-id from to]}
        (:query parameters)
        txn (config request)
        result (let-nom>
                 [_ (banks/get-bank txn bank-id)
                  found (audit/list-by-bank txn
                                            bank-id
                                            (assoc
                                             (cursor/page-opts page)
                                             :kinds kind
                                             :performer-kinds
                                             performed-by-kind
                                             :request-id request-id
                                             :from from
                                             :to to))
                  named (named-audit-entries txn (:audit-entries found))]
                 (cursor/page-body (cursor/request-path request)
                                   page
                                   named
                                   found))]
    (if (error/anomaly? result)
      (errors/anomaly->response result)
      {:status 200 :body result})))
