(ns com.repldriven.queenswood.zyphe-adapter.webhook.handlers
  (:require
    [com.repldriven.queenswood.zyphe-adapter.publisher :as publisher]

    [com.repldriven.queenswood.zyphe-relay.interface :as relay]
    [com.repldriven.queenswood.zyphe-webhook.interface :as zyphe-webhook]

    [com.repldriven.mono.avro.interface :as avro]
    [com.repldriven.mono.error.interface :as error :refer [let-nom>]]
    [com.repldriven.mono.log.interface :as log]
    [com.repldriven.mono.utility.interface :as utility]))

(defn- record-event
  "Serialise the event descriptor and write it to the outbox in one FDB
  transaction. A duplicate `dedup-key` (redelivered webhook) counts as
  success. Returns `:ok` or a non-dedup anomaly."
  [request {:keys [event-name dedup-key data]}]
  (let [{:keys [record-db record-store avro]} request
        schema (get avro event-name)]
    (if (nil? schema)
      (error/fail :zyphe-adapter/unknown-event
                  {:message "No schema for event" :event event-name})
      (let-nom> [payload (avro/serialize schema data)]
        (let [res (relay/save-event
                   {:record-db record-db :record-store record-store}
                   {:outbox-id (str (utility/uuidv7))
                    :dedup-key dedup-key
                    :event-name event-name
                    :payload payload
                    :correlation-id (str (utility/uuidv7))
                    :causation-id (str (utility/uuidv7))
                    :created-at (utility/now)})]
          (if (relay/uniqueness-violation? res)
            :ok
            res))))))

(defn- record
  [request event]
  (let [descriptor (publisher/->idv-completed event)]
    (if (nil? descriptor)
      (do (log/info "Zyphe webhook carries no decision; acknowledged"
                    {:event-id (:id event)
                     :type (:type event)
                     :flow-status (get-in event [:flow :status])})
          {:status 200 :body {:received true}})
      (let [res (record-event request descriptor)]
        (if (error/anomaly? res)
          (do (log/error "Failed to record idv-completed webhook" res)
              {:status 500 :body {:error "webhook not recorded"}})
          {:status 200 :body {:received true}})))))

(defn receive
  [_config]
  (fn [request]
    (let [{:keys [parameters headers raw-body webhook-secret]} request
          {:keys [body]} parameters
          verified (zyphe-webhook/verify webhook-secret
                                         (get headers "x-signature")
                                         (or raw-body (byte-array 0))
                                         (utility/now))]
      (log/info "Zyphe webhook received"
                {:event-id (:id body)
                 :type (:type body)
                 :flow-status (get-in body [:flow :status])})
      (if (error/anomaly? verified)
        (do (log/warn "Zyphe webhook refused" {:kind (error/kind verified)})
            {:status 401 :body {:error (:message (error/payload verified))}})
        (record request body)))))
