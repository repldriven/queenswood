(ns com.repldriven.queenswood.onfido-adapter.webhook.handlers
  (:require
    [com.repldriven.queenswood.onfido-adapter.publisher :as publisher]

    [com.repldriven.queenswood.intent-poller.interface :as intent-poller]
    [com.repldriven.queenswood.onfido-relay.interface :as relay]
    [com.repldriven.queenswood.onfido-webhook.interface :as onfido-webhook]

    [com.repldriven.mono.avro.interface :as avro]
    [com.repldriven.mono.error.interface :as error :refer [let-nom>]]
    [com.repldriven.mono.log.interface :as log]
    [com.repldriven.mono.utility.interface :as utility]))

(def ^:private run-completed "workflow_run.completed")

(defn- record-event
  "Serialise the event descriptor and write it to the outbox in one FDB
  transaction. A duplicate `dedup-key` (redelivered webhook) counts as
  success. Returns `:ok` or a non-dedup anomaly."
  [request {:keys [event-name dedup-key data]}]
  (let [{:keys [record-db record-store avro]} request
        schema (get avro event-name)]
    (if (nil? schema)
      (error/fail :onfido-adapter/unknown-event
                  {:message "No schema for event" :event event-name})
      (let-nom> [payload (avro/serialize schema data)]
        (let [res (relay/save-event
                   {:record-db record-db :record-store record-store}
                   (utility/assoc-some {:outbox-id (str (utility/uuidv7))
                                        :dedup-key dedup-key
                                        :event-name event-name
                                        :payload payload
                                        :correlation-id (str (utility/uuidv7))
                                        :causation-id (str (utility/uuidv7))
                                        :created-at (utility/now)}
                                       :ordering-key
                                       (intent-poller/ordering-key data)))]
          (if (relay/uniqueness-violation? res)
            :ok
            res))))))

(defn- record
  "Read the finished run back from Onfido, since the delivery names it
  and none of its results, and record its evidence."
  [request run-id]
  (let [{:keys [onfido-url api-token]} request
        res (let-nom> [read (relay/read-run {:onfido-url onfido-url
                                             :api-token api-token}
                                            run-id)]
              (if-let [descriptor (publisher/->idv-evidence read)]
                (record-event request descriptor)
                (log/info "Onfido run carries no evidence; acknowledged"
                          {:run-id run-id})))]
    (if (error/anomaly? res)
      (do (log/error "Failed to record Onfido evidence" res)
          {:status 500 :body {:error "webhook not recorded"}})
      {:status 200 :body {:received true}})))

(defn receive
  [_config]
  (fn [request]
    (let [{:keys [parameters headers raw-body webhook-token]} request
          {:keys [action object]} (get-in parameters [:body :payload])
          verified (onfido-webhook/verify webhook-token
                                          (get headers
                                               onfido-webhook/signature-header)
                                          (or raw-body (byte-array 0)))]
      (log/info "Onfido webhook received"
                {:action action :id (:id object) :status (:status object)})
      (cond
       (error/anomaly? verified)
       (do (log/warn "Onfido webhook refused" {:kind (error/kind verified)})
           {:status 401 :body {:error (:message (error/payload verified))}})

       (= run-completed action)
       (record request (:id object))

       :else
       {:status 200 :body {:received true}}))))
