(ns com.repldriven.queenswood.payment-api.interface
  "The payment resources as the banking API publishes them: the malli
  components the document's `OutboundPayment`, `InboundPayment` and
  `InternalPayment` and their request shapes are built from, the
  examples those shapes and the rejection bodies carry, the enum
  schemas that coerce a status and a scheme between wire strings and
  internal keywords, the OpenAPI `links` a submission advertises, and
  the projections that turn a stored payment into a response body —
  one in the record's own spelling, one in the wire's.

  Anything published about a payment is projected here, so a second
  surface — a webhook notification carrying the same resource — emits
  the same shape as the read routes rather than a copy of it."
  (:require
    [com.repldriven.queenswood.payment-api.coercion :as coercion]
    [com.repldriven.queenswood.payment-api.components :as components]
    [com.repldriven.queenswood.payment-api.examples :as examples]
    [com.repldriven.queenswood.payment-api.links :as links]))

;; ---
;; schemas
;; ---

(def
  ^{:doc
    "Malli registry of the payment schemas, keyed by the name each
  appears under in the document's `components/schemas` — the three
  payment resources, their ids, statuses and scheme, the two submission
  requests and the inbound list. Merged into the coercion registry in
  `api.clj`, so `[:ref \"X\"]` resolves them on any route."}
  registry
  components/registry)

(def
  ^{:doc
    "Map of example name to example value, feeding the document's
  `components/examples` section."}
  examples
  examples/registry)

;; ---
;; projections
;; ---

(defn ->outbound-body
  "Project a stored outbound payment onto the keys `OutboundPayment`
  declares, in the shape a read route returns.

  Args:
  - payment: an outbound payment as the query brick hands it back."
  [payment]
  (components/->outbound-body payment))

(defn ->inbound-body
  "Project a stored inbound payment onto the keys `InboundPayment`
  declares.

  Args:
  - payment: an inbound payment as the query brick hands it back."
  [payment]
  (components/->inbound-body payment))

(defn ->internal-body
  "Project a stored internal payment onto the keys `InternalPayment`
  declares.

  Args:
  - payment: an internal payment as the query brick hands it back."
  [payment]
  (components/->internal-body payment))

(defn ->outbound-wire-body
  "Project a stored outbound payment and encode it as a read route
  would: the status and scheme as their wire strings, timestamps as
  ISO-8601. The bytes a surface outside the API base renders.

  Args:
  - payment: an outbound payment as the query brick hands it back."
  [payment]
  (components/->outbound-wire-body payment))

(defn ->inbound-wire-body
  "As `->outbound-wire-body`, for an inbound payment.

  Args:
  - payment: an inbound payment as the query brick hands it back."
  [payment]
  (components/->inbound-wire-body payment))

;; ---
;; coercion
;; ---

(defn outbound-payment-status-enum-schema
  "The `OutboundPaymentStatus` `:enum` schema, coercing between the
  wire strings (`pending`, `processing`, `completed`, `failed`, `held`)
  and the record's prefixed keywords.

  Args:
  - extra-props (optional): map of schema properties merged over the
    enum's own, for a `:json-schema/example` and the like."
  ([] (coercion/outbound-payment-status-enum-schema))
  ([extra-props] (coercion/outbound-payment-status-enum-schema extra-props)))

(defn inbound-payment-status-enum-schema
  "The `InboundPaymentStatus` `:enum` schema, coercing between the wire
  strings (`settled`, `suspended`, `held`, `returned`) and the record's
  prefixed keywords.

  Args:
  - extra-props (optional): as `outbound-payment-status-enum-schema`."
  ([] (coercion/inbound-payment-status-enum-schema))
  ([extra-props] (coercion/inbound-payment-status-enum-schema extra-props)))

(defn payment-scheme-enum-schema
  "The `PaymentScheme` `:enum` schema, coercing between the wire string
  `fps` and the record's prefixed keyword.

  Args:
  - extra-props (optional): as `outbound-payment-status-enum-schema`."
  ([] (coercion/payment-scheme-enum-schema))
  ([extra-props] (coercion/payment-scheme-enum-schema extra-props)))

(defn encode-inbound-payment-status
  "The wire string of an inbound payment status keyword, or nil.

  Args:
  - status: the record's prefixed keyword."
  [status]
  (coercion/encode-inbound-payment-status status))

(defn encode-payment-scheme
  "The wire string of a payment scheme keyword, or nil. Required
  before Avro serialization of a submission.

  Args:
  - scheme: the record's prefixed keyword."
  [scheme]
  (coercion/encode-payment-scheme scheme))

;; ---
;; links
;; ---

(def ^{:doc "OpenAPI `links` a submitted internal payment response carries."}
     from-internal-payment
  links/from-internal-payment)

(def ^{:doc "OpenAPI `links` a submitted outbound payment response carries."}
     from-outbound-payment
  links/from-outbound-payment)

;; ---
;; examples the routes name
;; ---

(def ^{:doc "RFC 9457 body for a payment that does not exist — 404."}
     PaymentNotFound
  examples/PaymentNotFound)

(def ^{:doc "RFC 9457 body for a balance that does not exist — 404."}
     BalanceNotFound
  examples/BalanceNotFound)

(def ^{:doc "RFC 9457 body for an amount the transaction rules refuse."}
     InvalidAmount
  examples/InvalidAmount)

(def ^{:doc "An inbound payment settled on arrival."} SettledInboundPayment
  examples/SettledInboundPayment)

(def ^{:doc "An inbound payment parked in suspense."} SuspendedInboundPayment
  examples/SuspendedInboundPayment)

(def ^{:doc "An inbound payment held by the scheme."} HeldInboundPayment
  examples/HeldInboundPayment)

(def ^{:doc "An inbound payment returned to its remitter."}
     ReturnedInboundPayment
  examples/ReturnedInboundPayment)

(def ^{:doc "A page of inbound payments."} InboundPaymentList
  examples/InboundPaymentList)
