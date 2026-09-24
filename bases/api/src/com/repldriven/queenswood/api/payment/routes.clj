(ns com.repldriven.queenswood.api.payment.routes
  (:require
    [com.repldriven.queenswood.api.examples :as api.examples]
    [com.repldriven.queenswood.api.payment.commands :as commands]
    [com.repldriven.queenswood.api.payment.queries :as queries]

    [com.repldriven.queenswood.api.shared.headers :as shared.headers]
    [com.repldriven.queenswood.api.shared.idempotency :as shared.idempotency]
    [com.repldriven.queenswood.api.shared.parameters :as shared.parameters]

    [com.repldriven.queenswood.api-schema.interface :refer
     [ErrorExamples ErrorResponse SuccessResponse]]
    [com.repldriven.queenswood.payment-api.interface :as links :refer
     [BalanceNotFound CreditorAccountNotOperable CurrencyMismatch
      DebtorAccountNotOperable HeldInboundPayment InboundPaymentList
      InvalidAmount PaymentNotFound ReturnedInboundPayment
      SelfTransferNotPermitted
      SettledInboundPayment SuspendedInboundPayment]]
    [com.repldriven.queenswood.cash-account-api.interface :refer
     [CashAccountNotFound]]
    [com.repldriven.queenswood.idempotency.interface :as bank-idempotency]

    [com.repldriven.mono.server.interface :as server]))

(def ^:private list-inbound-query-schema
  [:map {:closed true}
   [:status [:ref "InboundPaymentStatus"]]
   [:page {:optional true} [:ref "PageQuery"]]])

(def routes
  [["/payments"
    {:openapi {:tags ["Payments"]}}
    ["/internal"
     {:openapi {:security [{"bearerAuth" ["org:developer"]}]}
      :post {:summary "Submit an internal payment"
             :openapi {:operationId "SubmitInternalPayment"
                       :description
                       (str "The payment settles as it is submitted, "
                            "debiting one of the bank's cash accounts and "
                            "crediting another. Both accounts must be opened, "
                            "or the payment is refused with 409. A "
                            "`payment.internal-settled` webhook notification "
                            "follows.")
                       :requestBody {:required true}
                       :parameters ^:replace
                                   [shared.parameters/ref-bank-id-header
                                    shared.parameters/ref-idempotency-key]}
             :interceptors [server/require-idempotency-key
                            bank-idempotency/cache-response]
             :parameters {:body [:ref "SubmitInternalPaymentRequest"]}
             :responses
             (shared.idempotency/with-responses
              {201 {:description "The settled payment."
                    :body [:ref "InternalPayment"]
                    :openapi {:headers {"Location" (shared.headers/location
                                                    "payment")}
                              :links links/from-internal-payment}}
               403 (ErrorExamples [#'api.examples/PolicyDenied])
               404 (ErrorResponse [#'CashAccountNotFound
                                   #'BalanceNotFound])
               409 (ErrorResponse [#'DebtorAccountNotOperable
                                   #'CreditorAccountNotOperable])
               422 (ErrorResponse [#'InvalidAmount #'SelfTransferNotPermitted
                                   #'CurrencyMismatch])
               429 (ErrorResponse [#'api.examples/PolicyLimitExceeded])})
             :handler commands/submit-internal-payment}}]
    ["/internal/{payment-id}"
     {:parameters {:path {:payment-id [:ref "PaymentId"]}}}
     [""
      {:openapi {:security [{"bearerAuth" ["org:viewer"]}]
                 :parameters [shared.parameters/ref-bank-id-header]}
       :get {:summary "Retrieve an internal payment"
             :openapi {:operationId "RetrieveInternalPayment"
                       :description
                       (str "An internal payment settles as it is submitted, "
                            "so it carries no status.")}
             :responses {200 {:description "The internal payment."
                              :body [:ref "InternalPayment"]}
                         404 (ErrorResponse [#'PaymentNotFound])}
             :handler queries/get-internal-payment}}]]
    ["/outbound"
     {:openapi {:security [{"bearerAuth" ["org:developer"]}]}
      :post
      {:summary "Submit an outbound payment"
       :openapi {:operationId "SubmitOutboundPayment"
                 :description
                 (str "The debtor account must be opened; otherwise the "
                      "payment is refused with 409. The amount is reserved "
                      "from the account's available balance and the payment is"
                      " returned `pending`. It moves to `held`, `completed` or"
                      " `failed` as the scheme responds, with a "
                      "`payment.outbound-status-changed` webhook notification "
                      "for each change. A failure releases the reservation.")
                 :requestBody {:required true}
                 :parameters ^:replace
                             [shared.parameters/ref-bank-id-header
                              shared.parameters/ref-idempotency-key]}
       :interceptors [server/require-idempotency-key
                      bank-idempotency/cache-response]
       :parameters {:body [:ref "SubmitOutboundPaymentRequest"]}
       :responses (shared.idempotency/with-responses
                   {201 {:description "The payment in the `pending` status."
                         :body [:ref "OutboundPayment"]
                         :openapi {:headers {"Location" (shared.headers/location
                                                         "payment")}
                                   :links links/from-outbound-payment}}
                    403 (ErrorExamples [#'api.examples/PolicyDenied])
                    404 (ErrorResponse [#'CashAccountNotFound
                                        #'BalanceNotFound])
                    409 (ErrorResponse [#'DebtorAccountNotOperable])
                    422 (ErrorResponse [#'InvalidAmount #'CurrencyMismatch])
                    429 (ErrorResponse [#'api.examples/PolicyLimitExceeded])})
       :handler commands/submit-outbound-payment}}]
    ["/outbound/{payment-id}"
     {:parameters {:path {:payment-id [:ref "PaymentId"]}}}
     [""
      {:openapi {:security [{"bearerAuth" ["org:viewer"]}]
                 :parameters [shared.parameters/ref-bank-id-header]}
       :get {:summary "Retrieve an outbound payment"
             :openapi {:operationId "RetrieveOutboundPayment"
                       :description
                       (str
                        "An outbound payment is `pending` until the scheme "
                        "responds, then `held`, `completed` or `failed`. A "
                        "failed payment carries the scheme's cancellation code "
                        "and reason.")}
             :responses {200 {:description "The outbound payment."
                              :body [:ref "OutboundPayment"]}
                         404 (ErrorResponse [#'PaymentNotFound])}
             :handler queries/get-outbound-payment}}]]
    ["/inbound"
     {:openapi {:security [{"bearerAuth" ["org:viewer"]}]}
      :get {:summary "List inbound payments"
            :openapi {:operationId "ListInboundPayments"
                      :description
                      (str "Money that arrived for the bank's accounts, in "
                           "the one status the `status` query parameter "
                           "names, newest first and a page at a time.")
                      :parameters ^:replace
                                  [shared.parameters/ref-inbound-payment-status
                                   shared.parameters/ref-page
                                   shared.parameters/ref-bank-id-header]}
            :parameters {:query list-inbound-query-schema}
            :responses {200 (SuccessResponse
                             "The bank's inbound payments in the status."
                             [:ref "InboundPaymentList"]
                             [#'InboundPaymentList])}
            :handler queries/list-inbound-payments}}]
    ["/inbound/{payment-id}"
     {:parameters {:path {:payment-id [:ref "PaymentId"]}}}
     [""
      {:openapi {:security [{"bearerAuth" ["org:viewer"]}]
                 :parameters [shared.parameters/ref-bank-id-header]}
       :get {:summary "Retrieve an inbound payment"
             :openapi {:operationId "RetrieveInboundPayment"
                       :description
                       (str "An inbound payment is settled, held while the "
                            "scheme screens it, returned to the remitter, or "
                            "suspended when no opened account could take it "
                            "or the bank's policies refused it.")}
             :responses {200 (SuccessResponse
                              "The inbound payment, in any status."
                              [:ref "InboundPayment"]
                              [#'SettledInboundPayment
                               #'SuspendedInboundPayment #'HeldInboundPayment
                               #'ReturnedInboundPayment])
                         404 (ErrorResponse [#'PaymentNotFound])}
             :handler queries/get-inbound-payment}}]]]])
