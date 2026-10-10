(ns com.repldriven.queenswood.api.payee-check.queries
  (:require
    [com.repldriven.queenswood.api.cursor :as cursor]
    [com.repldriven.queenswood.api.errors :as errors]

    [com.repldriven.queenswood.payee-check.interface :as payee-checks]

    [com.repldriven.mono.error.interface :as error]))

(defn- ->body
  [check]
  (assoc (select-keys check
                      [:check-id :request :result :created-at :created-by])
         :expires-at
         (payee-checks/expires-at check)))

(defn read-check
  "The `PayeeCheck` as a response."
  [request bank-id check-id]
  (let [{:keys [record-db record-store]} request
        config {:record-db record-db :record-store record-store}
        result (payee-checks/get-check config bank-id check-id)]
    (if (error/anomaly? result)
      (errors/anomaly->response result)
      {:status 200 :body (->body result)})))

(defn get-check
  [request]
  (let [{:keys [auth parameters]} request
        {:keys [bank-id]} auth
        {:keys [check-id]} (:path parameters)]
    (read-check request bank-id check-id)))

(defn list-checks
  [request]
  (let [{:keys [record-db record-store auth parameters]} request
        {:keys [bank-id]} auth
        {:keys [page]} (:query parameters)
        config {:record-db record-db :record-store record-store}
        result (payee-checks/get-checks config bank-id (cursor/page-opts page))]
    (if (error/anomaly? result)
      (errors/anomaly->response result)
      {:status 200
       :body
       (cursor/page-body "/v1/payee-checks"
                         page
                         (mapv ->body (:items result))
                         result)})))