(ns com.repldriven.queenswood.api.payment.components
  (:require
    [com.repldriven.queenswood.api.payment.coercion :as coercion]
    [com.repldriven.queenswood.api.payment.examples :as examples]

    [com.repldriven.queenswood.api-schema.interface :as schema :refer
     [components-registry]]))

(def PaymentId (schema/id-schema "PaymentId" "pmt" examples/PaymentId))

(def PaymentScheme
  (coercion/payment-scheme-enum-schema {:json-schema/example "fps"}))

(def SubmitInternalPaymentRequest
  [:map
   {:json-schema/example examples/SubmitInternalPaymentRequest}
   [:debtor-account-id [:ref "CashAccountId"]]
   [:creditor-account-id [:ref "CashAccountId"]]
   [:currency [:ref "Currency"]]
   [:amount [:ref "PaymentMinorUnits"]]
   [:reference {:optional true} [:maybe string?]]])

(def InternalPayment
  [:map {:json-schema/example examples/InternalPayment}
   [:payment-id [:ref "PaymentId"]]
   [:bank-id [:ref "BankId"]]
   [:debtor-account-id [:ref "CashAccountId"]]
   [:creditor-account-id [:ref "CashAccountId"]]
   [:currency [:ref "Currency"]]
   [:amount [:ref "MinorUnits"]]
   [:transaction-id [:ref "TransactionId"]]
   [:reference {:optional true} [:maybe string?]]
   [:business-day [:ref "BusinessDay"]]
   [:created-at [:ref "Timestamp"]]
   [:updated-at {:optional true} [:maybe [:ref "Timestamp"]]]])

(def OutboundPaymentStatus
  (coercion/outbound-payment-status-enum-schema {:json-schema/example
                                                 "pending"}))

(def SubmitOutboundPaymentRequest
  [:map
   {:json-schema/example examples/SubmitOutboundPaymentRequest}
   [:debtor-account-id [:ref "CashAccountId"]]
   [:creditor-bban [:ref "Bban"]]
   [:creditor-name [:ref "Name"]]
   [:currency [:ref "Currency"]]
   [:amount [:ref "PaymentMinorUnits"]]
   [:scheme [:ref "PaymentScheme"]]
   [:reference {:optional true} [:maybe string?]]])

(def OutboundPayment
  [:map {:json-schema/example examples/OutboundPayment}
   [:payment-id [:ref "PaymentId"]]
   [:bank-id [:ref "BankId"]]
   [:scheme [:ref "PaymentScheme"]]
   [:debtor-account-id [:ref "CashAccountId"]]
   [:creditor-bban [:ref "Bban"]]
   [:creditor-name [:ref "Name"]]
   [:currency [:ref "Currency"]]
   [:amount [:ref "MinorUnits"]]
   [:payment-status [:ref "OutboundPaymentStatus"]]
   [:transaction-id [:ref "TransactionId"]]
   [:reference {:optional true} [:maybe string?]]
   [:cancellation-code {:optional true} [:maybe string?]]
   [:cancellation-reason {:optional true} [:maybe string?]]
   [:business-day [:ref "BusinessDay"]]
   [:created-at {:optional true} [:maybe [:ref "Timestamp"]]]
   [:updated-at {:optional true} [:maybe [:ref "Timestamp"]]]])

(def registry
  (components-registry
   [#'PaymentId #'PaymentScheme #'SubmitInternalPaymentRequest #'InternalPayment
    #'OutboundPaymentStatus #'SubmitOutboundPaymentRequest #'OutboundPayment]))
