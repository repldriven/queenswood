(ns com.repldriven.queenswood.api.payee-check.queries
  (:require
    [com.repldriven.queenswood.api.cursor :as cursor]
    [com.repldriven.queenswood.api.errors :as errors]

    [com.repldriven.queenswood.payee-check.interface :as payee-checks]

    [com.repldriven.mono.error.interface :as error])
  (:import
    (java.time Instant)))

(defn- rfc3339
  [millis]
  (str (Instant/ofEpochMilli millis)))

(defn ->body
  "`check` as the payee-check routes return it: its id, request and
  result, and when it was made and stops standing as RFC 3339 text."
  [check]
  (-> (select-keys check [:check-id :request :result])
      (assoc :created-at (rfc3339 (:created-at check))
             :expires-at (rfc3339 (payee-checks/expires-at check)))))

(defn get-check
  [request]
  (let [{:keys [record-db record-store auth parameters]} request
        {:keys [bank-id]} auth
        {:keys [path]} parameters
        {:keys [check-id]} path
        config {:record-db record-db :record-store record-store}
        result (payee-checks/get-check config
                                       bank-id
                                       check-id)]
    (if (error/anomaly? result)
      (errors/anomaly->response result)
      {:status 200 :body (->body result)})))

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