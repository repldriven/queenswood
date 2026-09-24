(ns com.repldriven.queenswood.api.simulate.routes
  (:require
    [com.repldriven.queenswood.api.simulate.examples :refer
     [BalanceNotFound InvalidAmount LedgerAccountClosed
      MissingCurrencyAccount]]
    [com.repldriven.queenswood.api.simulate.handlers :as handlers]

    [com.repldriven.queenswood.api.bank.examples :refer
     [BankNotFound BankUnnamed]]
    [com.repldriven.queenswood.api.shared.idempotency :as shared.idempotency]
    [com.repldriven.queenswood.api.shared.interceptors :as shared.interceptors]
    [com.repldriven.queenswood.api.shared.parameters :as shared.parameters]

    [com.repldriven.queenswood.api-schema.interface :refer
     [ErrorExamples ErrorResponse]]
    [com.repldriven.queenswood.idempotency.interface :as bank-idempotency]

    [com.repldriven.mono.server.interface :as server]))

(def routes
  [["/simulate/inbound-transfer"
    ;; Sandbox affordance: a bank tenant funds its own bank from the
    ;; console, so the route drops to `org:developer` beside `admin`.
    ;; `named-bank` refuses an operator who names no bank.
    {:openapi {:tags ["Simulate"]
               :security [{"bearerAuth" ["org:developer"]}
                          {"bearerAuth" ["admin"]}]}
     :interceptors [shared.interceptors/named-bank]
     :post
     {:summary "Simulate an inbound transfer into the bank's own funds"
      :openapi {:operationId "SimulateInboundTransfer"
                :description
                (str "Credits the own-funds account of the bank the "
                     "`Bank-Id` header names, in the given currency, as "
                     "money arriving from outside, and returns the posted "
                     "transaction with the account it credited. An operator "
                     "naming no bank is refused with 403. A currency the bank"
                     " has no account in is refused with 409.")
                :requestBody {:required true}
                :parameters ^:replace
                            [shared.parameters/ref-bank-id-header
                             shared.parameters/ref-idempotency-key]}
      :parameters {:body [:ref "SimulateInboundTransferRequest"]}
      :interceptors [server/require-idempotency-key
                     bank-idempotency/cache-response]
      :responses (shared.idempotency/with-responses
                  {200 {:description
                        "The posted transaction and the account it credited."
                        :body [:ref "SimulateInboundTransferResponse"]}
                   403 (ErrorExamples [#'BankUnnamed])
                   404 (ErrorResponse [#'BankNotFound #'BalanceNotFound])
                   409 (ErrorResponse [#'MissingCurrencyAccount
                                       #'LedgerAccountClosed])
                   422 (ErrorResponse [#'InvalidAmount])})
      :handler handlers/inbound-transfer}}]])
