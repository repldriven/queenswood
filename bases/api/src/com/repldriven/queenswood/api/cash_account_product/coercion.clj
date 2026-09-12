(ns com.repldriven.queenswood.api.cash-account-product.coercion
  (:require
    [com.repldriven.queenswood.api-schema.interface :as coercion]))

(def ^:private balance-sheet-side-enum
  (coercion/enum-coercion {"asset" :balance-sheet-side-asset
                           "liability" :balance-sheet-side-liability}
                          :balance-sheet-side-unknown))

(def ^:private version-status-enum
  (coercion/enum-coercion {"draft" :cash-account-product-status-draft
                           "published" :cash-account-product-status-published
                           "discarded" :cash-account-product-status-discarded}
                          :cash-account-product-status-unknown))

(def balance-sheet-side-enum-schema (:enum-schema balance-sheet-side-enum))
(def version-status-enum-schema (:enum-schema version-status-enum))
