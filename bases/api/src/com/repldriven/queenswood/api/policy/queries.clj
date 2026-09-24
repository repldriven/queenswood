(ns com.repldriven.queenswood.api.policy.queries
  (:require
    [com.repldriven.queenswood.api.cursor :as cursor]
    [com.repldriven.queenswood.api.errors :as errors]

    [com.repldriven.queenswood.policy.interface :as policies]

    [com.repldriven.mono.error.interface :as error]))

(defn list-policies
  [request]
  (let [{:keys [record-db record-store parameters]} request
        {:keys [page]} (:query parameters)
        config {:record-db record-db :record-store record-store}
        result (policies/get-policies config (cursor/page-opts page))]
    (if (error/anomaly? result)
      (errors/anomaly->response result)
      {:status 200
       :body (cursor/page-body "/v1/policies" page (:items result) result)})))

(defn list-bank-policies
  "The policies effective for the bank the request names — the always-on
  platform tier plus any policies bound to the bank."
  [request]
  (let [{:keys [record-db record-store auth]} request
        {:keys [bank-id]} auth
        config {:record-db record-db :record-store record-store}
        result (policies/get-effective-policies config {:bank-id bank-id})]
    (if (error/anomaly? result)
      (errors/anomaly->response result)
      {:status 200 :body {:items result}})))

(defn get-effective-policy
  "The bank's effective policies collapsed into the resolved decision
  set — `{:capabilities [...] :limits [...]}`, one survivor per scope,
  each tagged with its origin policy. Reads `bank-id` from the auth as
  `list-bank-policies` does."
  [request]
  (let [{:keys [record-db record-store auth]} request
        {:keys [bank-id]} auth
        config {:record-db record-db :record-store record-store}
        result (policies/get-effective-policy config {:bank-id bank-id})]
    (if (error/anomaly? result)
      (errors/anomaly->response result)
      {:status 200 :body result})))

(defn get-policy
  [request]
  (let [{:keys [record-db record-store parameters]} request
        {:keys [path]} parameters
        {:keys [policy-id]} path
        config {:record-db record-db :record-store record-store}
        result (policies/get-policy config policy-id)]
    (if (error/anomaly? result)
      (errors/anomaly->response result)
      {:status 200 :body result})))
