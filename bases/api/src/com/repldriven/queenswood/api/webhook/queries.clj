(ns com.repldriven.queenswood.api.webhook.queries
  (:require
    [com.repldriven.queenswood.api.cursor :as cursor]
    [com.repldriven.queenswood.api.errors :as errors]

    [com.repldriven.queenswood.webhook.interface :as webhook]

    [com.repldriven.mono.error.interface :as error]))

(defn- config
  [{:keys [record-db record-store]}]
  {:record-db record-db :record-store record-store})

(defn- listing
  "One 200 body for the delivery history: the window's items, and the
  cursor links when there is a page either side of it."
  [items id-key project path page]
  (let [windowed (cursor/window items id-key :asc page)]
    {:status 200
     :body (cursor/page-body path
                             page
                             (mapv project (:page windowed))
                             windowed)}))

(defn list-endpoints
  [request]
  (let [{:keys [auth parameters]} request
        {:keys [bank-id]} auth
        {:keys [page]} (:query parameters)
        result (webhook/get-endpoints (config request)
                                      bank-id
                                      (cursor/page-opts page))]
    (if (error/anomaly? result)
      (errors/anomaly->response result)
      {:status 200
       :body (cursor/page-body "/v1/webhook-endpoints"
                               page
                               (mapv webhook/->body (:endpoints result))
                               result)})))

(defn get-endpoint
  [request]
  (let [{:keys [auth parameters]} request
        {:keys [bank-id]} auth
        {:keys [endpoint-id]} (:path parameters)
        result (webhook/get-endpoint (config request) bank-id endpoint-id)]
    (if (error/anomaly? result)
      (errors/anomaly->response result)
      {:status 200 :body (webhook/->body result)})))

(defn list-deliveries
  [request]
  (let [{:keys [auth parameters]} request
        {:keys [bank-id]} auth
        {:keys [endpoint-id]} (:path parameters)
        {:keys [page]} (:query parameters)
        filters (get-in parameters [:query :filter])
        result (webhook/get-deliveries (config request)
                                       bank-id
                                       endpoint-id
                                       (or filters {}))]
    (if (error/anomaly? result)
      (errors/anomaly->response result)
      (listing (:deliveries result)
               :delivery-id
               webhook/->delivery-body
               (cursor/request-path request)
               page))))
