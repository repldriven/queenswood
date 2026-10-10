(ns com.repldriven.queenswood.api.party.queries
  (:require
    [com.repldriven.queenswood.api.cursor :as cursor]
    [com.repldriven.queenswood.api.errors :as errors]

    [com.repldriven.queenswood.idv-query.interface :as idv-query]
    [com.repldriven.queenswood.party-api.interface :as party-api]
    [com.repldriven.queenswood.party-query.interface :as parties]
    [com.repldriven.queenswood.policy.interface :as policy]

    [com.repldriven.mono.error.interface :as error :refer [let-nom>]]
    [com.repldriven.mono.utility.interface :as utility]))

(defn list-parties
  [request]
  (let [{:keys [auth parameters]} request
        {:keys [bank-id]} auth
        {:keys [page]} (:query parameters)
        result (parties/get-parties request bank-id (cursor/page-opts page))]
    (if (error/anomaly? result)
      (errors/anomaly->response result)
      {:status 200
       :body (cursor/page-body "/v1/parties"
                               page
                               (mapv party-api/->body (:parties result))
                               result)})))

(defn read-party
  "The `Party` as a response, as the list and the writes return it."
  [request bank-id party-id]
  (let [result (parties/get-party request bank-id party-id)]
    (if (error/anomaly? result)
      (errors/anomaly->response result)
      {:status 200 :body (party-api/->body result)})))

(defn get-party
  [request]
  (let [{:keys [auth parameters]} request
        {:keys [bank-id]} auth
        {:keys [path query]} parameters
        {:keys [party-id]} path
        {:keys [embed]} query
        result (parties/get-party-detail request
                                         bank-id
                                         party-id
                                         (select-keys
                                          embed
                                          [:legal-name]))]
    (if (error/anomaly? result)
      (errors/anomaly->response result)
      {:status 200 :body (party-api/->detail-body result)})))

(defn- party-idv
  [request bank-id party-id]
  (let-nom> [idv (idv-query/get-idv-by-party request party-id)]
    (if (and idv (= bank-id (:bank-id idv)))
      idv
      (error/reject :idv/not-found
                    {:message "No verification for this party"
                     :party-id party-id}))))

(defn get-verification
  [request]
  (let [{:keys [auth parameters]} request
        {:keys [bank-id]} auth
        {:keys [party-id]} (:path parameters)
        result (let-nom>
                 [idv (party-idv request bank-id party-id)
                  policies (policy/get-effective-policies request
                                                          {:bank-id bank-id})]
                 (party-api/->verification-body
                  party-id
                  (idv-query/verification idv policies)))]
    (if (error/anomaly? result)
      (errors/anomaly->response result)
      {:status 200 :body result})))

(defn read-verification-session
  "The party's `VerificationSession` as a response."
  [request bank-id party-id session-id]
  (let [result (let-nom>
                 [session (idv-query/get-session request bank-id session-id)]
                 (if (and session (= party-id (:party-id session)))
                   (party-api/->session-body
                    (idv-query/session session (utility/now)))
                   (error/reject :idv/session-not-found
                                 {:message "No such verification session"
                                  :session-id session-id})))]
    (if (error/anomaly? result)
      (errors/anomaly->response result)
      {:status 200 :body result})))

(defn get-verification-session
  [request]
  (let [{:keys [auth parameters]} request
        {:keys [bank-id]} auth
        {:keys [party-id session-id]} (:path parameters)]
    (read-verification-session request bank-id party-id session-id)))
