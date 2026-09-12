(ns com.repldriven.queenswood.webhook.events
  (:require
    [com.repldriven.queenswood.webhook.catalogue :as catalogue]
    [com.repldriven.queenswood.webhook.domain :as domain]
    [com.repldriven.queenswood.webhook.store :as store]

    [com.repldriven.mono.avro.interface :as avro]
    [com.repldriven.mono.error.interface :as error :refer [let-nom>]]
    [com.repldriven.mono.log.interface :as log]
    [com.repldriven.mono.processor.interface :as processor]
    [com.repldriven.mono.utility.interface :as utility]

    [clojure.string :as str]))

(def ^:private enabled :webhook-endpoint-status-enabled)

(def ^:private endpoint-page-size
  "How many endpoints one scan of a bank's list reads. How many a bank
  may hold is a policy limit rather than a constant, so the scan pages
  to the end rather than assuming one page reaches it."
  100)

(defn- enabled-endpoints
  "Every enabled endpoint of `bank-id` that has chosen `kind`. An
  endpoint that chose no kinds has chosen all of them."
  [txn bank-id kind]
  (loop [cursor nil
         found []]
    (let [page (store/get-endpoints txn
                                    bank-id
                                    (utility/assoc-some
                                     {:limit endpoint-page-size}
                                     :after
                                     cursor))]
      (if (error/anomaly? page)
        page
        (let [found (into found
                          (filter (fn [endpoint]
                                    (and (= enabled (:status endpoint))
                                         (domain/chosen? endpoint kind))))
                          (:endpoints page))]
          (if-let [next-cursor (:after page)]
            (recur next-cursor found)
            found))))))

(defn- correlation-id
  "The envelope's correlating id, or the changelog entry's own id when
  it carries none. `ChangelogEvent` declares `correlation_id` optional
  and the cash-account writer sets none, so without the fallback the
  field REQ-017 names would be absent from every notification this
  slice produces."
  [envelope]
  (let [{:keys [correlation-id id]} envelope]
    (if (str/blank? correlation-id) id correlation-id)))

(defn- notification-row
  [entry envelope data record now]
  (let [status-name (:status-name entry)]
    (utility/assoc-some
     {:bank-id (:bank-id data)
      :notification-id (utility/generate-id "whn")
      :kind (:kind entry)
      :change-kind (:published-change-kind entry)
      :resource-type (:resource-type entry)
      :resource-id (get data (:resource-id-key entry))
      :occurred-at now
      :changelog-event-id (:id envelope)
      :created-at now}
     :status-before (status-name (:status-before data))
     :status-after (status-name (:status-after data))
     :idempotency-key (:idempotency-key record)
     :correlation-id (correlation-id envelope))))

(defn- save-deliveries
  [txn notification endpoints now]
  (reduce (fn [_ endpoint]
            (let [res (store/save-delivery txn
                                           (domain/new-delivery notification
                                                                endpoint
                                                                now))]
              (if (error/anomaly? res) (reduced res) nil)))
          nil
          endpoints))

(defn- write
  "The notification and one pending delivery per subscribed endpoint,
  in one transaction. The record is loaded inside it and its
  idempotency key read before projection, because `->body` drops the
  key."
  [config entry envelope data]
  (let [bank-id (:bank-id data)
        resource-id (get data (:resource-id-key entry))
        now (utility/now)]
    (store/transact
     config
     (fn [txn]
       (let-nom>
         [record ((:load entry) txn bank-id resource-id)
          _ (or record
                (error/fail :webhook/resource-missing
                            {:message "Resource the event named is not there"
                             :bank-id bank-id
                             :resource-type (:resource-type entry)
                             :resource-id resource-id}))
          endpoints (enabled-endpoints txn bank-id (:kind entry))
          row (notification-row entry envelope data record now)
          body (domain/notification-body row
                                         ((:project entry) record))
          row (assoc row :body body)
          _ (store/save-notification txn row)
          _ (save-deliveries txn row endpoints now)]
         row)))))

(defn- write-once
  "Write, treating the unique index's refusal as the no-op it is: a
  redelivered or redriven event has already produced its notification,
  and this consume acknowledges without producing a second."
  [config entry envelope data]
  (let [result (write config entry envelope data)]
    (if (store/uniqueness-violation? result)
      (log/info "Webhook notification already written for this event"
                {:changelog-event-id (:id envelope) :kind (:kind entry)})
      result)))

(defn- handle
  [config entry envelope data]
  (if (= (:terminal-status entry) (:status-after data))
    (write-once config entry envelope data)
    (log/debugf "Skipping the %s leg of %s, which lands on %s"
                (:kind entry)
                (:event entry)
                (:status-after data))))

(defn- dispatch
  [config message]
  (let [{:keys [event payload]} message
        {:keys [schemas]} config]
    (cond
     (not (catalogue/covers-event? event))
     (log/debugf "No webhook catalogue entry for event: %s" event)

     (nil? (get schemas event))
     (error/fail :webhook/unregistered-schema
                 {:message "Catalogued event has no registered payload schema"
                  :event event})

     :else
     (let-nom> [data (avro/deserialize-same (get schemas event) payload)]
       (if-let [entry (catalogue/find-entry event (:change-kind data))]
         (handle config entry message data)
         (log/debugf "No webhook catalogue entry for %s change kind: %s"
                     event
                     (:change-kind data)))))))

(defrecord WebhookEventProcessor [config]
  processor/Processor
    (process [_ message] (dispatch config message)))
