(ns com.repldriven.queenswood.api.simulate.components
  (:require
    [com.repldriven.queenswood.api.simulate.examples :as examples]

    [com.repldriven.queenswood.api-schema.interface :refer
     [components-registry]]))

(def SimulateInboundTransferRequest
  [:map
   {:closed true :json-schema/example examples/SimulateInboundTransferRequest}
   [:account-id [:ref "CashAccountId"]]
   [:amount [:ref "PaymentMinorUnits"]]
   [:currency [:ref "Currency"]]])

(def TransactionLeg
  [:map
   [:leg-id [:ref "LegId"]]
   [:transaction-id [:ref "TransactionId"]]
   ;; A leg targets any account in the shared account-id space: a
   ;; customer/house cash account (acc.) or a bank-owned ledger
   ;; control/detail account (led.) added by control fan-out.
   [:account-id [:or [:ref "CashAccountId"] [:ref "LedgerAccountId"]]]
   [:balance-type [:ref "BalanceType"]]
   [:balance-status [:ref "BalanceStatus"]]
   [:side [:ref "LegSide"]]
   [:amount [:ref "MinorUnits"]]
   [:currency [:ref "Currency"]]
   [:created-at {:optional true} [:maybe [:ref "Timestamp"]]]])

(def SimulateInboundTransferResponse
  [:map {:json-schema/example examples/SimulateInboundTransferResponse}
   [:transaction-id [:ref "TransactionId"]]
   [:status [:ref "TransactionStatus"]]
   [:transaction-type [:ref "TransactionType"]]
   [:currency [:ref "Currency"]]
   [:reference {:optional true} [:maybe string?]]
   [:created-at {:optional true} [:maybe [:ref "Timestamp"]]]
   [:updated-at {:optional true} [:maybe [:ref "Timestamp"]]]
   [:legs [:vector [:ref "TransactionLeg"]]]])

(def SimulateInterestRequest
  [:map {:closed true :json-schema/example examples/SimulateInterestRequest}
   [:as-of-date [:ref "BusinessDay"]]])

(def SimulateInterestResponse
  [:map {:json-schema/example examples/SimulateInterestResponse}
   [:bank-id [:ref "BankId"]]
   [:as-of-date [:ref "BusinessDay"]]
   [:accounts-processed int?]])

(def registry
  (components-registry [#'SimulateInboundTransferRequest #'TransactionLeg
                        #'SimulateInboundTransferResponse
                        #'SimulateInterestRequest #'SimulateInterestResponse]))
