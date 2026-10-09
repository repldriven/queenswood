(ns com.repldriven.queenswood.cash-account-product-api.coercion
  (:require
    [com.repldriven.queenswood.api-schema.interface :as coercion]))

(def ^:private balance-sheet-side-enum
  (coercion/enum-coercion {"asset" :balance-sheet-side-asset
                           "liability" :balance-sheet-side-liability}
                          :balance-sheet-side-unknown))

(def ^:private version-status-enum
  (coercion/enum-coercion {"draft" :version-status-draft
                           "published" :version-status-published
                           "discarded" :version-status-discarded}
                          :version-status-unknown))

(def balance-sheet-side-enum-schema (:enum-schema balance-sheet-side-enum))
(def version-status-enum-schema (:enum-schema version-status-enum))
