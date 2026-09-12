(ns com.repldriven.queenswood.api.access.coercion
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

(def ^:private actor-kind-enum
  (coercion/enum-coercion {"member" :actor-kind-member
                           "operator" :actor-kind-operator}
                          :actor-kind-unknown))

(def actor-kind-enum-schema (:enum-schema actor-kind-enum))

(def ^:private access-event-kind-enum
  (coercion/enum-coercion
   {"bank-created" :access-event-kind-bank-created
    "invitation-created" :access-event-kind-invitation-created
    "invitation-resent" :access-event-kind-invitation-resent
    "invitation-accepted" :access-event-kind-invitation-accepted
    "invitation-declined" :access-event-kind-invitation-declined
    "invitation-withdrawn" :access-event-kind-invitation-withdrawn
    "role-changed" :access-event-kind-role-changed
    "member-removed" :access-event-kind-member-removed
    "member-left" :access-event-kind-member-left}
   :access-event-kind-unknown))

(def access-event-kind-enum-schema (:enum-schema access-event-kind-enum))
