(ns com.repldriven.queenswood.payment-api.coercion
  (:require
    [com.repldriven.queenswood.api-schema.interface :as coercion]))

(def ^:private outbound-payment-status-enum
  (coercion/enum-coercion {"pending" :outbound-payment-status-pending
                           "processing" :outbound-payment-status-processing
                           "completed" :outbound-payment-status-completed
                           "failed" :outbound-payment-status-failed
                           "held" :outbound-payment-status-held
                           "returned" :outbound-payment-status-returned}
                          :outbound-payment-status-unknown))

(def outbound-payment-status-enum-schema
  (:enum-schema outbound-payment-status-enum))

(def ^:private inbound-payment-status-enum
  (coercion/enum-coercion {"settled" :inbound-payment-status-settled
                           "suspended" :inbound-payment-status-suspended
                           "held" :inbound-payment-status-held
                           "returned" :inbound-payment-status-returned}
                          :inbound-payment-status-unknown))

(def inbound-payment-status-enum-schema
  (:enum-schema inbound-payment-status-enum))

(defn encode-inbound-payment-status
  [status]
  (some-> ((:encode inbound-payment-status-enum) status)
          name))

(def ^:private outbound-payment-failure-kind-enum
  (coercion/enum-coercion {"declined" :outbound-payment-failure-kind-declined
                           "refused" :outbound-payment-failure-kind-refused
                           "undelivered"
                           :outbound-payment-failure-kind-undelivered}
                          :outbound-payment-failure-kind-unknown))

(def outbound-payment-failure-kind-enum-schema
  (:enum-schema outbound-payment-failure-kind-enum))

(def ^:private payment-scheme-enum
  (coercion/enum-coercion {"fps" :payment-scheme-fps} :payment-scheme-unknown))

(def payment-scheme-enum-schema (:enum-schema payment-scheme-enum))

(defn encode-payment-scheme
  "Convert a decoded payment scheme keyword back to its wire string,
  e.g. :payment-scheme-fps -> \"fps\". Required before Avro serialization."
  [scheme]
  (some-> ((:encode payment-scheme-enum) scheme)
          name))
