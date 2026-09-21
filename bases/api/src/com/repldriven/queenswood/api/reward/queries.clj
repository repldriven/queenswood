(ns com.repldriven.queenswood.api.reward.queries
  (:require
    [com.repldriven.queenswood.api.errors :as errors]

    [com.repldriven.queenswood.reward-query.interface :as rewards]

    [com.repldriven.mono.error.interface :as error]))

(def ^:private not-found
  {:status 404
   :body (errors/error-response 404 "REJECTED"
                                "reward/not-found" "Reward not found")})

(defn get-reward
  [request]
  (let [{:keys [auth parameters]} request
        {:keys [bank-id]} auth
        {:keys [reward-id]} (:path parameters)
        result (rewards/find-reward request bank-id reward-id)]
    (cond
     (error/anomaly? result)
     (errors/anomaly->response result)
     (nil? result)
     not-found
     :else
     {:status 200 :body result})))

(defn list-rewards
  [request]
  (let [{:keys [auth parameters]} request
        {:keys [bank-id]} auth
        {:keys [account-id]} (:query parameters)
        result (rewards/find-rewards-by-account request bank-id account-id)]
    (if (error/anomaly? result)
      (errors/anomaly->response result)
      {:status 200 :body {:items result}})))
