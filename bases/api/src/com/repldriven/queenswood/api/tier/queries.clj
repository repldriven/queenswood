(ns com.repldriven.queenswood.api.tier.queries
  (:require
    [com.repldriven.queenswood.api.errors :as errors]

    [com.repldriven.queenswood.policy.interface :as policies]

    [com.repldriven.mono.error.interface :as error]))

(defn list-tiers
  [request]
  (let [{:keys [record-db record-store]} request
        config {:record-db record-db :record-store record-store}
        result (policies/get-tiers config)]
    (if (error/anomaly? result)
      (errors/anomaly->response result)
      {:status 200 :body {:items (or result [])}})))
