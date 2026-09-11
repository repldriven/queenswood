(ns com.repldriven.queenswood.api.payee-check.coercion
  (:require
    [com.repldriven.queenswood.api-schema.interface :as coercion]))

(def ^:private match-result-enum
  (coercion/enum-coercion {"match" :match-result-match
                           "close-match" :match-result-close-match
                           "no-match" :match-result-no-match
                           "unavailable" :match-result-unavailable}
                          :match-result-unknown))

(def match-result-enum-schema (:enum-schema match-result-enum))

(def ^:private account-type-enum
  (coercion/enum-coercion {"personal" :account-type-personal
                           "business" :account-type-business}
                          :account-type-unknown))

(def account-type-enum-schema (:enum-schema account-type-enum))
