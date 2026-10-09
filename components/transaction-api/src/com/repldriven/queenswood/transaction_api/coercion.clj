(ns com.repldriven.queenswood.transaction-api.coercion
  (:require
    [com.repldriven.queenswood.api-schema.interface :as coercion]))

(def ^:private transaction-type-enum
  (coercion/enum-coercion
   {"internal-transfer" :transaction-type-internal-transfer
    "inbound-transfer" :transaction-type-inbound-transfer
    "outbound-transfer" :transaction-type-outbound-transfer
    "fee" :transaction-type-fee
    "interest-accrual" :transaction-type-interest-accrual
    "interest-capitalization" :transaction-type-interest-capitalization
    "reward" :transaction-type-reward
    "outbound-return" :transaction-type-outbound-return
    "inbound-return" :transaction-type-inbound-return}
   :transaction-type-unknown))

(def ^:private leg-side-enum
  (coercion/enum-coercion {"debit" :leg-side-debit "credit" :leg-side-credit}
                          :leg-side-unknown))

(def transaction-type-enum-schema (:enum-schema transaction-type-enum))
(def leg-side-enum-schema (:enum-schema leg-side-enum))
