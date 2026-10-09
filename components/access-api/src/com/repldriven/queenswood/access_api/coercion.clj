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

(def ^:private actor-kind-enum
  (coercion/enum-coercion {"member" :actor-kind-member
                           "operator" :actor-kind-operator
                           "bank" :actor-kind-bank}
                          :actor-kind-unknown))

(def actor-kind-enum-schema (:enum-schema actor-kind-enum))

(def ^:private audit-event-kind-enum
  (coercion/enum-coercion
   {"bank-created" :audit-event-kind-bank-created
    "invitation-created" :audit-event-kind-invitation-created
    "invitation-resent" :audit-event-kind-invitation-resent
    "invitation-accepted" :audit-event-kind-invitation-accepted
    "invitation-declined" :audit-event-kind-invitation-declined
    "invitation-withdrawn" :audit-event-kind-invitation-withdrawn
    "role-changed" :audit-event-kind-role-changed
    "member-removed" :audit-event-kind-member-removed
    "member-left" :audit-event-kind-member-left}
   :audit-event-kind-unknown))

(def audit-event-kind-enum-schema (:enum-schema audit-event-kind-enum))
