(ns com.repldriven.queenswood.api.simulate.routes
  (:require
    [com.repldriven.queenswood.api.simulate.handlers :as handlers]

    [com.repldriven.queenswood.api.shared.idempotency :as shared.idempotency]
    [com.repldriven.queenswood.api.shared.interceptors :as shared.interceptors]
    [com.repldriven.queenswood.api.shared.parameters :as shared.parameters]

    [com.repldriven.queenswood.api-schema.interface :refer
     [ErrorExamples ErrorResponse]]
    [com.repldriven.queenswood.bank-api.interface :refer
     [BankNotFound BankUnnamed]]
    [com.repldriven.queenswood.idempotency.interface :as bank-idempotency]
    [com.repldriven.queenswood.payment-api.interface :refer
     [CreditorAccountNotOperable]]
    [com.repldriven.queenswood.simulate-api.interface :refer
     [MissingCurrencyAccount SimulateLiveBank]]

    [com.repldriven.mono.server.interface :as server]))

(def routes
  [["/simulate/inbound-transfer"
    ;; Sandbox affordance: a bank tenant funds its own test bank from
    ;; the console, so the route drops to `org:developer` beside `admin`.
    ;; `named-bank` refuses an operator who names no bank, and
    ;; `test-bank` a live one.
    {:openapi {:tags ["Simulate"]
               :security [{"bearerAuth" ["org:developer"]}
                          {"bearerAuth" ["admin"]}]}
     :interceptors [shared.interceptors/named-bank
                    shared.interceptors/test-bank]
     :post {:summary "Simulate an inbound payment to a test bank's own funds"
            :openapi {:operationId "SimulateInboundTransfer"
                      :description
                      (str
                       "Asks the payment provider of the test bank the "
                       "`Bank-Id` header names to send a payment in to the "
                       "bank's own-funds account in the given currency, and "
                       "returns 202 with the account it is addressed to. The "
                       "money arrives a moment later as an inbound payment, "
                       "held to the bank's inbound payment limits as any "
                       "other is, and nothing arrives where the bank's "
                       "provider is not a sandbox. An operator naming no bank "
                       "is refused with 403. A live bank is refused with 409, "
                       "as is a currency the bank has no account in, and a "
                       "bank whose own-funds account has not opened yet.")
                      :requestBody {:required true}
                      :parameters ^:replace
                                  [shared.parameters/ref-bank-id-header
                                   shared.parameters/ref-idempotency-key]}
            :parameters {:body [:ref "SimulateInboundTransferRequest"]}
            :interceptors [server/require-idempotency-key
                           bank-idempotency/cache-response]
            :responses (shared.idempotency/with-responses
                        {202 {:description
                              (str "The payment the provider was asked to send "
                                   "in, and the account it is addressed to.")
                              :body [:ref "SimulateInboundTransferResponse"]}
                         403 (ErrorExamples [#'BankUnnamed])
                         404 (ErrorResponse [#'BankNotFound])
                         409 (ErrorResponse [#'SimulateLiveBank
                                             #'MissingCurrencyAccount
                                             #'CreditorAccountNotOperable])})
            :handler handlers/inbound-transfer}}]])
