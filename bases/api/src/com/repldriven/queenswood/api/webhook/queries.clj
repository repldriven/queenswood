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

(defn- paginate
  "Windows a seq of items already in ascending `id-key` order, using
  `page[after|before|size]` cursor semantics. `size` caps the page.

  For the delivery history alone: an endpoint list pages through the
  store's own cursor scan, and only deliveries are read whole."
  [items id-key {:keys [after before size]}]
  (let [limit (cursor/clamp-size size)]
    (cond
     after
     (let [rest-items (drop-while (fn [item]
                                    (not (pos? (compare (id-key item) after))))
                                  items)
           page (vec (take limit rest-items))]
       {:page page
        :before (when (seq page) (id-key (first page)))
        :after (when (> (count rest-items) limit) (id-key (last page)))})

     before
     (let [earlier (take-while (fn [item]
                                 (neg? (compare (id-key item) before)))
                               items)
           page (vec (take-last limit earlier))]
       {:page page
        :before (when (> (count earlier) limit) (id-key (first page)))
        :after (when (seq page) (id-key (last page)))})

     :else
     (let [page (vec (take limit items))]
       {:page page
        :before nil
        :after (when (> (count items) limit) (id-key (last page)))}))))

(defn- listing
  "One 200 body for the delivery history: the window's items, and the
  cursor links when there is a page either side of it."
  [items id-key project path page]
  (let [{:keys [after before size]} page
        {windowed :page next-cursor :after prev-cursor :before}
        (paginate items
                  id-key
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
