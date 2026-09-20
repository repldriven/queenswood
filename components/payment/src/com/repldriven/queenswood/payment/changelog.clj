(ns com.repldriven.queenswood.payment.changelog
  (:require
    [com.repldriven.queenswood.schema.interface :as schema]

    [com.repldriven.mono.avro.interface :as avro]
    [com.repldriven.mono.error.interface :as error :refer [let-nom>]]
    [com.repldriven.mono.telemetry.interface :as telemetry]
    [com.repldriven.mono.utility.interface :as utility]

    [clojure.java.io :as io]))

(def ^:private outbound-event-name "outbound-payment-status-changed")

(def ^:private inbound-event-name "inbound-payment-status-changed")

;; Loaded from the classpath rather than the injected `avro/serde`: the
;; payload schema is a property of this brick, and `store.clj` only ever
;; receives a Txn, never the system config the serde arrives in.
(defn- load-schema
  [path]
  (delay (avro/json->schema (slurp (io/resource path)))))

(def ^:private outbound-schema
  (load-schema "schemas/payments/outbound-payment-status-changed.avsc.json"))

(def ^:private inbound-schema
  (load-schema "schemas/payments/inbound-payment-status-changed.avsc.json"))

(defn- entry
  "The shared-envelope changelog bytes for a payment write. `changelog`
  carries `:bank-id`, `:payment-id`, `:status-before`, `:status-after`,
  `:change-kind` and `:updated-at`; `store.clj` supplies everything but
  the kind and the status before off the saved record, and the caller
  passes those two.

  A missing `:change-kind` is an error anomaly rather than a null in
  the payload: a consumer telling one transition from another reads
  the kind, not the pair of statuses."
  [event-name schema
   {:keys [bank-id payment-id status-before status-after
           change-kind updated-at]}]
  (if-not change-kind
    (error/fail :payment/changelog
                {:message "Payment changelog entry needs a change kind"
                 :payment-id payment-id
                 :status-after status-after})
    (let-nom> [payload (avro/serialize @schema
                                       {:bank-id bank-id
                                        :payment-id payment-id
                                        :status-before status-before
                                        :status-after status-after
                                        :change-kind change-kind})]
      (schema/ChangelogEvent->pb
       (utility/assoc-some
        {:event-id (str (utility/uuidv7))
         :dedup-key (str payment-id ":" (name change-kind) ":" updated-at)
         :event-name event-name
         :payload payload
         :causation-id payment-id
         :ordering-key payment-id
         :created-at (utility/now)}
        ;; Written inside the processor's transaction, so this is the
        ;; processing span. The relay republishes it and the consumer's
        ;; span joins that trace.
        :traceparent
        (telemetry/inject-traceparent))))))

(defn outbound-changed
  [changelog]
  (entry outbound-event-name outbound-schema changelog))

(defn inbound-changed
  [changelog]
  (entry inbound-event-name inbound-schema changelog))
