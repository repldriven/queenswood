(ns com.repldriven.queenswood.api.transaction.coercion
  (:require
    [com.repldriven.queenswood.api-schema.interface :as coercion]))

(def ^:private transaction-status-enum
  (coercion/enum-coercion {"pending" :transaction-status-pending
                           "posted" :transaction-status-posted
                           "failed" :transaction-status-failed
                           "reversed" :transaction-status-reversed}
                          :transaction-status-unknown))

(def ^:private transaction-type-enum
  (coercion/enum-coercion
   {"internal-transfer" :transaction-type-internal-transfer
    "inbound-transfer" :transaction-type-inbound-transfer
    "outbound-transfer" :transaction-type-outbound-transfer
    "fee" :transaction-type-fee
    "interest-accrual" :transaction-type-interest-accrual
    "interest-capital" :transaction-type-interest-capital}
   :transaction-type-unknown))

(def ^:private leg-side-enum
  (coercion/enum-coercion {"debit" :leg-side-debit "credit" :leg-side-credit}
                          :leg-side-unknown))

(def transaction-status-enum-schema (:enum-schema transaction-status-enum))
(def transaction-type-enum-schema (:enum-schema transaction-type-enum))
(def leg-side-enum-schema (:enum-schema leg-side-enum))
