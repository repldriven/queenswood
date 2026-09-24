(ns com.repldriven.queenswood.payment-api.components
  (:require
    [com.repldriven.queenswood.payment-api.coercion :as coercion]
    [com.repldriven.queenswood.payment-api.examples :as examples]

    [com.repldriven.queenswood.api-schema.interface :as schema :refer
     [components-registry]]
    [com.repldriven.queenswood.cash-account-api.interface :as
     cash-account-api]))

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

(def InboundPaymentStatus
  (coercion/inbound-payment-status-enum-schema {:json-schema/example
                                                "settled"}))

(def InboundPayment
  [:map {:json-schema/example examples/InboundPayment}
   [:payment-id [:ref "PaymentId"]]
   [:bank-id [:ref "BankId"]]
   [:scheme string?]
   [:scheme-transaction-id string?]
   [:end-to-end-id string?]
   [:creditor-account-id {:optional true} [:maybe [:ref "CashAccountId"]]]
   [:currency [:ref "Currency"]]
   [:amount [:ref "MinorUnits"]]
   [:payment-status [:ref "InboundPaymentStatus"]]
   [:transaction-id {:optional true} [:maybe [:ref "TransactionId"]]]
   [:debtor-name {:optional true} [:maybe string?]]
   [:reference {:optional true} [:maybe string?]]
   [:business-day [:ref "BusinessDay"]]
   [:created-at [:ref "Timestamp"]]
   [:updated-at [:ref "Timestamp"]]])

(def InboundPaymentList
  (schema/list-schema "InboundPayment" (:value examples/InboundPaymentList)))

(def registry
  (components-registry
   [#'PaymentId #'PaymentScheme #'SubmitInternalPaymentRequest #'InternalPayment
    #'OutboundPaymentStatus #'SubmitOutboundPaymentRequest #'OutboundPayment
    #'InboundPaymentStatus #'InboundPayment #'InboundPaymentList]))

(defn- declared-keys
  [component]
  (into [] (comp (filter vector?) (map first)) component))

(def ^:private outbound-payment-keys (declared-keys OutboundPayment))

(def ^:private inbound-payment-keys (declared-keys InboundPayment))

(def ^:private internal-payment-keys (declared-keys InternalPayment))

(defn ->outbound-body
  [payment]
  (select-keys payment outbound-payment-keys))

(defn ->inbound-body
  [payment]
  (select-keys payment inbound-payment-keys))

(defn ->internal-body
  [payment]
  (select-keys payment internal-payment-keys))

(def ^:private wire-registry
  "What the wire encoders resolve a `$ref` against: the shared schemas,
  the account ids a payment names, this brick's own, and a
  `TransactionId` of its own, since the transaction domain's shapes
  are still declared inside the API base and a component cannot reach
  them. The document's `TransactionId` stays the transaction domain's;
  this one encodes and is published nowhere."
  (merge schema/registry
         cash-account-api/registry
         {"TransactionId" (schema/id-schema "TransactionId"
                                            "txn"
                                            "txn.01kprbmgcj35ptc8npmybhh4s9")}
         registry))

(def ^:private encode-outbound
  (schema/api-encoder OutboundPayment wire-registry))

(def ^:private encode-inbound (schema/api-encoder InboundPayment wire-registry))

(defn ->outbound-wire-body
  [payment]
  (encode-outbound (->outbound-body payment)))

(defn ->inbound-wire-body
  [payment]
  (encode-inbound (->inbound-body payment)))

(def ^:private encode-internal
  (schema/api-encoder InternalPayment wire-registry))

(defn ->internal-wire-body
  [payment]
  (encode-internal (->internal-body payment)))
