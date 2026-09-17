(ns com.repldriven.queenswood.api.webhook.queries
  (:require
    [com.repldriven.queenswood.api.cursor :as cursor]
    [com.repldriven.queenswood.api.errors :as errors]

    [com.repldriven.queenswood.webhook.interface :as webhook]

    [com.repldriven.mono.error.interface :as error]
    [com.repldriven.mono.utility.interface :as utility]))

(defn- config
  [{:keys [record-db record-store]}]
  {:record-db record-db :record-store record-store})

(defn- deliveries-path
  [endpoint-id]
  (str "/v1/webhook-endpoints/" endpoint-id "/deliveries"))

(defn- listing
  "One 200 body for the delivery history: the window's items, and the
  cursor links when there is a page either side of it."
  [items id-key project path page]
  (let [{:keys [after before size]} page
        {windowed :page next-cursor :after prev-cursor :before}
        (cursor/paginate items
                         id-key
                         :asc
                         {:after (cursor/decode after)
                          :before (cursor/decode before)
                          :size size})
        links (when (seq windowed)
                (cursor/build-links path
                                    (cursor/clamp-size size)
                                    (when after prev-cursor)
                                    next-cursor))]
    {:status 200
     :body (utility/assoc-seq {:items (mapv project windowed)} :links links)}))

(defn list-endpoints
  [request]
  (let [{:keys [auth parameters]} request
        {:keys [bank-id]} auth
        {:keys [page]} (:query parameters)
        {:keys [after before size]} page
        after-id (cursor/decode after)
        before-id (cursor/decode before)
        size (cursor/clamp-size size)
        opts (utility/assoc-some {:limit size}
                                 :after after-id
                                 :before before-id)
        result (webhook/get-endpoints (config request) bank-id opts)]
    (if (error/anomaly? result)
      (errors/anomaly->response result)
      (let [{:keys [endpoints] next-cursor :after prev-cursor :before} result
            links (when (seq endpoints)
                    (cursor/build-links "/v1/webhook-endpoints"
                                        size
                                        (when after-id prev-cursor)
                                        next-cursor))]
        {:status 200
         :body (utility/assoc-seq {:items (mapv webhook/->body endpoints)}
                                  :links
                                  links)}))))

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
               (deliveries-path endpoint-id)
               page))))
