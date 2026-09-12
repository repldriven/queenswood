(ns com.repldriven.queenswood.api.payment.routes
  (:require
    [com.repldriven.queenswood.api.payment.commands :as commands]
    [com.repldriven.queenswood.api.payment.examples :refer
     [AlreadySubmitted BalanceNotFound InvalidAmount PaymentNotFound]]
    [com.repldriven.queenswood.api.payment.links :as links]
    [com.repldriven.queenswood.api.payment.queries :as queries]

    [com.repldriven.queenswood.api.shared.idempotency :as shared.idempotency]
    [com.repldriven.queenswood.api.shared.parameters :as shared.parameters]

    [com.repldriven.queenswood.api-schema.interface :refer [ErrorResponse]]
    [com.repldriven.queenswood.cash-account-api.interface :refer
     [CashAccountNotFound]]
    [com.repldriven.queenswood.idempotency.interface :as bank-idempotency]

    [com.repldriven.mono.server.interface :as server]))

(def routes
  [["/payments"
    {:openapi {:tags ["Payments"] :security [{"bearerAuth" ["org"]}]}}
    ["/internal"
     {:post {:summary "Submit an internal payment"
             :openapi {:operationId "SubmitInternalPayment"
                       :requestBody {:required true}
                       :parameters ^:replace
                                   [shared.parameters/ref-idempotency-key]}
             :interceptors [server/require-idempotency-key
                            bank-idempotency/cache-response]
             :parameters {:body [:ref "SubmitInternalPaymentRequest"]}
             :responses (shared.idempotency/with-responses
                         {200 {:body [:ref "InternalPayment"]
                               :openapi {:links links/from-internal-payment}}
                          404 (ErrorResponse [#'CashAccountNotFound
                                              #'BalanceNotFound])
                          409 (ErrorResponse [#'AlreadySubmitted])
                          422 (ErrorResponse [#'InvalidAmount])})
             :handler commands/submit-internal-payment}}]
    ["/internal/{payment-id}"
     {:parameters {:path {:payment-id [:ref "PaymentId"]}}}
     [""
      {:get {:summary "Retrieve an internal payment"
             :openapi {:operationId "RetrieveInternalPayment"}
             :responses {200 {:body [:ref "InternalPayment"]}
                         404 (ErrorResponse [#'PaymentNotFound])}
             :handler queries/get-internal-payment}}]]
    ["/outbound"
     {:post {:summary "Submit an outbound payment"
             :openapi {:operationId "SubmitOutboundPayment"
                       :requestBody {:required true}
                       :parameters ^:replace
                                   [shared.parameters/ref-idempotency-key]}
             :interceptors [server/require-idempotency-key
                            bank-idempotency/cache-response]
             :parameters {:body [:ref "SubmitOutboundPaymentRequest"]}
             :responses (shared.idempotency/with-responses
                         {200 {:body [:ref "OutboundPayment"]
                               :openapi {:links links/from-outbound-payment}}
                          404 (ErrorResponse [#'CashAccountNotFound
                                              #'BalanceNotFound])
                          409 (ErrorResponse [#'AlreadySubmitted])
                          422 (ErrorResponse [#'InvalidAmount])})
             :handler commands/submit-outbound-payment}}]
    ["/outbound/{payment-id}"
     {:parameters {:path {:payment-id [:ref "PaymentId"]}}}
     [""
      {:get {:summary "Retrieve an outbound payment"
             :openapi {:operationId "RetrieveOutboundPayment"}
             :responses {200 {:body [:ref "OutboundPayment"]}
                         404 (ErrorResponse [#'PaymentNotFound])}
             :handler queries/get-outbound-payment}}]]]])
