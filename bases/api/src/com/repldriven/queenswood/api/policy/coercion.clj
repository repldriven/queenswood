(ns com.repldriven.queenswood.api.policy.coercion
  (:require
    [com.repldriven.queenswood.api-schema.interface :as coercion]))

(def ^:private policy-category-enum
  (coercion/enum-coercion {"standard" :policy-category-standard
                           "restricted" :policy-category-restricted
                           "emergency" :policy-category-emergency}
                          :policy-category-unknown))

(def policy-category-enum-schema (:enum-schema policy-category-enum))
