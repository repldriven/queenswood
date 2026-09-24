(ns com.repldriven.queenswood.api.party.queries
  (:require
    [com.repldriven.queenswood.api.cursor :as cursor]
    [com.repldriven.queenswood.api.errors :as errors]

    [com.repldriven.queenswood.party-query.interface :as parties]

    [com.repldriven.mono.error.interface :as error]))

(defn list-parties
  [request]
  (let [{:keys [auth parameters]} request
        {:keys [bank-id]} auth
        {:keys [page]} (:query parameters)
        result (parties/get-parties request bank-id (cursor/page-opts page))]
    (if (error/anomaly? result)
      (errors/anomaly->response result)
      {:status 200
       :body (cursor/page-body "/v1/parties" page (:parties result) result)})))

(defn get-party
  [request]
  (let [{:keys [auth parameters]} request
        {:keys [bank-id]} auth
        {:keys [path query]} parameters
        {:keys [party-id]} path
        {:keys [embed]} query
        {pi :person-identification addr :address ni :national-identifier} embed
        result (parties/get-party-detail request
                                         bank-id
                                         party-id
                                         {:person-identification pi
                                          :address addr
                                          :national-identifier ni})]
    (if (error/anomaly? result)
      (errors/anomaly->response result)
      {:status 200 :body result})))
