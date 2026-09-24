(ns com.repldriven.queenswood.api.payee-check.queries
  (:require
    [com.repldriven.queenswood.api.cursor :as cursor]
    [com.repldriven.queenswood.api.errors :as errors]

    [com.repldriven.queenswood.payee-check.interface :as payee-checks]

    [com.repldriven.mono.error.interface :as error]))

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
      {:status 200 :body result})))

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
       (cursor/page-body "/v1/payee-checks" page (:items result) result)})))