(ns com.repldriven.queenswood.api.cash-account-product.coercion
  (:require
    [com.repldriven.queenswood.api-schema.interface :as coercion]))

(def ^:private product-type-enum
  (coercion/enum-coercion {"current" :product-type-sub-ledger-current
                           "savings" :product-type-sub-ledger-savings
                           "term-deposit" :product-type-sub-ledger-term-deposit
                           "own-funds" :product-type-sub-ledger-own-funds}
                          :product-type-unknown))

(def ^:private balance-sheet-side-enum
  (coercion/enum-coercion {"asset" :balance-sheet-side-asset
                           "liability" :balance-sheet-side-liability}
                          :balance-sheet-side-unknown))

(def ^:private payment-address-scheme-enum
  (coercion/enum-coercion {"scan" :payment-address-scheme-scan
                           "iban" :payment-address-scheme-iban
                           "swift" :payment-address-scheme-swift
                           "ach" :payment-address-scheme-ach}
                          :payment-address-scheme-unknown))

(def ^:private version-status-enum
  (coercion/enum-coercion {"draft" :cash-account-product-status-draft
                           "published" :cash-account-product-status-published
                           "discarded" :cash-account-product-status-discarded}
                          :cash-account-product-status-unknown))

(def product-type-enum-schema (:enum-schema product-type-enum))
(def balance-sheet-side-enum-schema (:enum-schema balance-sheet-side-enum))
(def payment-address-scheme-enum-schema
  (:enum-schema payment-address-scheme-enum))
(def version-status-enum-schema (:enum-schema version-status-enum))
