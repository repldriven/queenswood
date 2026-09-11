(ns com.repldriven.queenswood.api.transaction.components
  (:require
    [com.repldriven.queenswood.api.transaction.coercion :as coercion]

    [com.repldriven.queenswood.api-schema.interface :as schema :refer
     [components-registry]]))

(def TransactionId
  (schema/id-schema "TransactionId" "txn" "txn.01kprbmgcj35ptc8npmybhh4sb"))

(def LegId (schema/id-schema "LegId" "leg" "leg.01kprbmgcj35ptc8npmybhh4sc"))

(def TransactionStatus
  (coercion/transaction-status-enum-schema {:json-schema/example "posted"}))

(def TransactionType
  (coercion/transaction-type-enum-schema {:json-schema/example
                                          "internal-transfer"}))

(def LegSide (coercion/leg-side-enum-schema {:json-schema/example "debit"}))

(def Transaction
  [:map
   [:leg-id [:ref "LegId"]]
   [:transaction-id [:ref "TransactionId"]]
   [:transaction-type [:ref "TransactionType"]]
   [:status [:ref "TransactionStatus"]]
   ;; A leg targets any account in the shared account-id space: a
   ;; customer/house cash account (acc.) or a bank-owned ledger
   ;; control/detail account (led.) added by control fan-out.
   [:account-id [:or [:ref "CashAccountId"] [:ref "LedgerAccountId"]]]
   [:balance-type [:ref "BalanceType"]]
   [:balance-status [:ref "BalanceStatus"]]
   [:side [:ref "LegSide"]]
   [:amount [:ref "MinorUnits"]]
   [:currency [:ref "CurrencyCode"]]
   [:reference {:optional true} [:maybe string?]]
   [:created-at [:ref "Timestamp"]]])

(def TransactionList
  [:map
   [:transactions [:vector [:ref "Transaction"]]]])

(def registry
  (components-registry [#'TransactionId #'LegId #'TransactionStatus
                        #'TransactionType #'LegSide #'Transaction
                        #'TransactionList]))
