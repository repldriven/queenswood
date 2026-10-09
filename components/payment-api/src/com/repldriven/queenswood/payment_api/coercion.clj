(ns com.repldriven.queenswood.payment-api.coercion
  (:require
    [com.repldriven.queenswood.api-schema.interface :as coercion]))

(def ^:private outbound-payment-status-enum
  (coercion/enum-coercion {"pending" :outbound-payment-status-pending
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
                           "returned" :inbound-payment-status-returned
                           "admitted" :inbound-payment-status-admitted
                           "return-failed"
                           :inbound-payment-status-return-failed}
                          :inbound-payment-status-unknown))

(def inbound-payment-status-enum-schema
  (:enum-schema inbound-payment-status-enum))

(defn encode-inbound-payment-status
  [status]
  (some-> ((:encode inbound-payment-status-enum) status)
          name))

(def ^:private outbound-payment-failed-kind-enum
  (coercion/enum-coercion {"declined" :outbound-payment-failed-kind-declined
                           "refused" :outbound-payment-failed-kind-refused
                           "undelivered"
                           :outbound-payment-failed-kind-undelivered}
                          :outbound-payment-failed-kind-unknown))

(def outbound-payment-failed-kind-enum-schema
  (:enum-schema outbound-payment-failed-kind-enum))

(def ^:private scheme-type-enum
  (coercion/enum-coercion {"fps" :scheme-type-fps} :scheme-type-unknown))

(def scheme-type-enum-schema (:enum-schema scheme-type-enum))
