(ns com.repldriven.queenswood.payment-api.links
  "OpenAPI 3 `links` objects for payment responses.")

(def from-internal-payment
  "Links available on a submitted internal payment response."
  {"GetPayment" {:operationId "RetrieveInternalPayment"
                 :parameters {"payment-id" "$response.body#/payment-id"}}})

(def from-outbound-payment
  "Links available on a submitted outbound payment response."
  {"GetPayment" {:operationId "RetrieveOutboundPayment"
                 :parameters {"payment-id" "$response.body#/payment-id"}}})
