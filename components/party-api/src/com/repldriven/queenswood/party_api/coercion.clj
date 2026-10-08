(ns com.repldriven.queenswood.party-api.coercion
  (:require
    [com.repldriven.queenswood.api-schema.interface :as coercion]))

(def ^:private party-type-enum
  (coercion/enum-coercion {"person" :party-type-person
                           "internal" :party-type-internal
                           "organization" :party-type-organization}
                          :party-type-unknown))

(def ^:private party-status-enum
  (coercion/enum-coercion {"pending" :party-status-pending
                           "active" :party-status-active
                           "suspended" :party-status-suspended
                           "closed" :party-status-closed
                           "rejected" :party-status-rejected
                           "merged" :party-status-merged}
                          :party-status-unknown))

(def ^:private verification-status-enum
  (coercion/enum-coercion {"pending" :idv-status-pending
                           "in-review" :idv-status-in-review
                           "accepted" :idv-status-accepted
                           "rejected" :idv-status-rejected
                           "failed" :idv-status-failed}
                          :idv-status-unknown))

(def ^:private verification-channel-enum
  (coercion/enum-coercion {"web" :idv-session-channel-web
                           "mobile" :idv-session-channel-mobile}
                          :idv-session-channel-unknown))

(def ^:private verification-session-status-enum
  (coercion/enum-coercion {"opening" :idv-session-status-opening
                           "ready" :idv-session-status-ready
                           "expired" :idv-session-status-expired
                           "completed" :idv-session-status-completed
                           "failed" :idv-session-status-failed}
                          :idv-session-status-unknown))

(def ^:private hand-off-type-enum
  (coercion/enum-coercion {"url" :idv-hand-off-kind-url}
                          :idv-hand-off-kind-unknown))

(def ^:private criterion-state-enum
  (coercion/enum-coercion {"outstanding" :idv-criterion-status-outstanding
                           "established" :idv-criterion-status-established
                           "in-review" :idv-criterion-status-review
                           "failed" :idv-criterion-status-failed}
                          :idv-criterion-status-unknown))

(def decode-party-type (:decode party-type-enum))
(def party-type-json-schema (:json-schema party-type-enum))
(def party-type-enum-schema (:enum-schema party-type-enum))

(def party-status-enum-schema (:enum-schema party-status-enum))


(def verification-status-enum-schema (:enum-schema verification-status-enum))

(def verification-channel-enum-schema (:enum-schema verification-channel-enum))

(def verification-session-status-enum-schema
  (:enum-schema verification-session-status-enum))

(def hand-off-type-enum-schema (:enum-schema hand-off-type-enum))

(def criterion-state-enum-schema (:enum-schema criterion-state-enum))
