(ns com.repldriven.queenswood.payment-api.components
  (:require
    [com.repldriven.queenswood.payment-api.coercion :as coercion]
    [com.repldriven.queenswood.payment-api.examples :as examples]

    [com.repldriven.queenswood.api-schema.interface :as schema :refer
     [components-registry]]
    [com.repldriven.queenswood.cash-account-api.interface :as
     cash-account-api]
    [com.repldriven.queenswood.transaction-api.interface :as
     transaction-api]))

(def PaymentId (schema/id-schema "PaymentId" "pmt" examples/PaymentId))

(def SchemeType (coercion/scheme-type-enum-schema {:json-schema/example "fps"}))

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

(def OutboundPaymentFailedKind
  (coercion/outbound-payment-failed-kind-enum-schema {:json-schema/example
                                                      "declined"}))

(def SubmitOutboundPaymentRequest
  [:map
   {:json-schema/example examples/SubmitOutboundPaymentRequest}
   [:debtor-account-id [:ref "CashAccountId"]]
   [:creditor-bban [:ref "Bban"]]
   [:creditor-name [:ref "Name"]]
   [:currency [:ref "Currency"]]
   [:amount [:ref "PaymentMinorUnits"]]
   [:scheme-type [:ref "SchemeType"]]
   [:reference {:optional true} [:maybe string?]]])

(def OutboundPayment
  [:map {:json-schema/example examples/OutboundPayment}
   [:payment-id [:ref "PaymentId"]]
   [:bank-id [:ref "BankId"]]
   [:status [:ref "OutboundPaymentStatus"]]
   [:scheme-type [:ref "SchemeType"]]
   [:debtor-account-id [:ref "CashAccountId"]]
   [:creditor-name [:ref "Name"]]
   [:creditor-bban [:ref "Bban"]]
   [:amount [:ref "MinorUnits"]]
   [:currency [:ref "Currency"]]
   [:reference {:optional true} string?]
   [:transaction-id [:ref "TransactionId"]]
   [:business-day [:ref "BusinessDay"]]
   [:failed-kind {:optional true} [:ref "OutboundPaymentFailedKind"]]
   [:failed-reason-code {:optional true} string?]
   [:failed-reason {:optional true} string?]
   [:returned-reason-code {:optional true} string?]
   [:returned-reason {:optional true} string?]
   [:created-at [:ref "Timestamp"]]
   [:updated-at {:optional true} [:ref "Timestamp"]]])

(def InboundPaymentStatus
  (coercion/inbound-payment-status-enum-schema {:json-schema/example
                                                "settled"}))

(def InboundPayment
  [:map {:json-schema/example examples/InboundPayment}
   [:payment-id [:ref "PaymentId"]]
   [:bank-id [:ref "BankId"]]
   [:status [:ref "InboundPaymentStatus"]]
   [:scheme-type [:ref "SchemeType"]]
   [:creditor-account-id {:optional true} [:ref "CashAccountId"]]
   [:debtor-name {:optional true} string?]
   [:amount [:ref "MinorUnits"]]
   [:currency [:ref "Currency"]]
   [:reference {:optional true} string?]
   [:end-to-end-id string?]
   [:scheme-transaction-id string?]
   [:transaction-id {:optional true} [:ref "TransactionId"]]
   [:business-day [:ref "BusinessDay"]]
   [:suspended-reason-code {:optional true} string?]
   [:suspended-reason {:optional true} string?]
   [:return-failed-reason {:optional true} string?]
   [:created-at [:ref "Timestamp"]]
   [:updated-at {:optional true} [:ref "Timestamp"]]])

(def InboundPaymentList
  (schema/list-schema "InboundPayment" (:value examples/InboundPaymentList)))

(def registry
  (components-registry
   [#'PaymentId #'SchemeType #'SubmitInternalPaymentRequest #'InternalPayment
    #'OutboundPaymentStatus #'OutboundPaymentFailedKind
    #'SubmitOutboundPaymentRequest #'OutboundPayment #'InboundPaymentStatus
    #'InboundPayment #'InboundPaymentList]))

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
  the account and transaction ids a payment names, and this brick's own."
  (merge schema/registry
         cash-account-api/registry
         transaction-api/registry
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
