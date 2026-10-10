(ns com.repldriven.queenswood.access-api.coercion
  (:require
    [com.repldriven.queenswood.api-schema.interface :as coercion]))

(def ^:private invitation-status-enum
  (coercion/enum-coercion {"pending" :invitation-status-pending
                           "accepted" :invitation-status-accepted
                           "declined" :invitation-status-declined
                           "withdrawn" :invitation-status-withdrawn
                           "expired" :invitation-status-expired}
                          :invitation-status-unknown))

(def invitation-status-enum-schema (:enum-schema invitation-status-enum))
