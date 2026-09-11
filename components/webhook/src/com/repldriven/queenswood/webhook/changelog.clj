(ns com.repldriven.queenswood.webhook.changelog
  (:require
    [com.repldriven.queenswood.schema.interface :as schema]

    [com.repldriven.mono.avro.interface :as avro]
    [com.repldriven.mono.error.interface :refer [let-nom>]]
    [com.repldriven.mono.telemetry.interface :as telemetry]
    [com.repldriven.mono.utility.interface :as utility]

    [clojure.java.io :as io]))

(def ^:private event-name "webhook-endpoint-status-changed")

;; Loaded from the classpath rather than the injected `avro/serde`: the
;; payload schema is a property of this brick, and `store.clj` only ever
;; receives a Txn, never the system config the serde arrives in.
(def ^:private schema
  (delay (avro/json->schema
          (slurp (io/resource
                  "schemas/webhooks/endpoint-status-changed.avsc.json")))))

(defn endpoint-status-changed
  "Build the shared-envelope changelog bytes for an endpoint's status
  transition. `changelog` carries `:bank-id`, `:endpoint-id`,
  `:status-before`, `:status-after` and `:updated-at`; `store.clj`
  supplies `:bank-id` and `:updated-at` off the saved record."
  [{:keys [bank-id endpoint-id status-before status-after updated-at]}]
  (let-nom> [payload (avro/serialize @schema
                                     {:bank-id bank-id
                                      :endpoint-id endpoint-id
                                      :status-before status-before
                                      :status-after status-after})]
    (schema/ChangelogEvent->pb
     (utility/assoc-some
      {:event-id (str (utility/uuidv7))
       :dedup-key (str endpoint-id ":" (name status-after) ":" updated-at)
       :event-name event-name
       :payload payload
       :causation-id endpoint-id
       :ordering-key endpoint-id
       :created-at (utility/now)}
      :traceparent
      (telemetry/inject-traceparent)))))
