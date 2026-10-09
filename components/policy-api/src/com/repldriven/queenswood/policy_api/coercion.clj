(ns com.repldriven.queenswood.policy-api.coercion
  (:require
    [com.repldriven.queenswood.api-schema.interface :as coercion]))

(def ^:private policy-category-enum
  (coercion/enum-coercion {"standard" :policy-category-standard
                           "restricted" :policy-category-restricted
                           "emergency" :policy-category-emergency}
                          :policy-category-unknown))

(def policy-category-enum-schema (:enum-schema policy-category-enum))

(def ^:private policy-status-enum
  (coercion/enum-coercion {"active" :policy-status-active
                           "disabled" :policy-status-disabled
                           "archived" :policy-status-archived}
                          :policy-status-unknown))

(def policy-status-enum-schema (:enum-schema policy-status-enum))
