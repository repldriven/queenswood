(ns com.repldriven.queenswood.company-api.coercion
  (:require
    [com.repldriven.queenswood.api-schema.interface :as coercion]))

(def ^:private company-registry-enum
  (coercion/enum-coercion {"uk-companies-house"
                           :company-registry-uk-companies-house}
                          :company-registry-unknown))

(def company-registry-enum-schema (:enum-schema company-registry-enum))
