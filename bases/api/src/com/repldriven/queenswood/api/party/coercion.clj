(ns com.repldriven.queenswood.api.party.coercion
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

(def ^:private identifier-type-enum
  (coercion/enum-coercion {"national-insurance"
                           :identifier-type-national-insurance
                           "passport" :identifier-type-passport
                           "driving-licence" :identifier-type-driving-licence
                           "national-id-card" :identifier-type-national-id-card
                           "tax-id" :identifier-type-tax-id}
                          :identifier-type-unknown))

(def decode-party-type (:decode party-type-enum))
(def party-type-json-schema (:json-schema party-type-enum))
(def party-type-enum-schema (:enum-schema party-type-enum))

(def party-status-enum-schema (:enum-schema party-status-enum))

(def identifier-type-enum-schema (:enum-schema identifier-type-enum))
