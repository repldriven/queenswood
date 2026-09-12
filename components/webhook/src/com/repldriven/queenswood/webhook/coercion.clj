(ns com.repldriven.queenswood.webhook.coercion
  (:require
    [com.repldriven.queenswood.api-schema.interface :as coercion]))

(def ^:private endpoint-status-enum
  (coercion/enum-coercion {"enabled" :webhook-endpoint-status-enabled
                           "disabled" :webhook-endpoint-status-disabled
                           "paused" :webhook-endpoint-status-paused
                           "removed" :webhook-endpoint-status-removed}
                          :webhook-endpoint-status-unknown))

(def endpoint-status-enum-schema (:enum-schema endpoint-status-enum))

(def ^:private delivery-status-enum
  (coercion/enum-coercion {"pending" :webhook-delivery-status-pending
                           "in-flight" :webhook-delivery-status-in-flight
                           "delivered" :webhook-delivery-status-delivered
                           "failed" :webhook-delivery-status-failed}
                          :webhook-delivery-status-unknown))

(def delivery-status-enum-schema (:enum-schema delivery-status-enum))

(def delivery-status->keyword (:decode delivery-status-enum))
