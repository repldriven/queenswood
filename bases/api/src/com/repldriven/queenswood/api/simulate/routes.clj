(ns com.repldriven.queenswood.api.simulate.routes
  (:require
    [com.repldriven.queenswood.api.simulate.examples :refer
     [BalanceNotFound InvalidAmount SettlementAccountNotFound
      TransactionAlreadyRecorded]]
    [com.repldriven.queenswood.api.simulate.handlers :as handlers]

    [com.repldriven.queenswood.api.bank.examples :refer [BankNotFound]]
    [com.repldriven.queenswood.api.schema :refer [ErrorResponse]]
    [com.repldriven.queenswood.api.shared.parameters :as shared.parameters]

    [com.repldriven.queenswood.idempotency.interface :as bank-idempotency]

    [com.repldriven.mono.server.interface :as server]))

(def routes
  [["/simulate"
    {:openapi {:tags ["Simulate"] :security [{"bearerAuth" ["admin"]}]}}
    ["/banks/{bank-id}"
     {:parameters {:path {:bank-id [:ref "BankId"]}}}
     ["/inbound-transfer"
      ;; Sandbox affordance: a bank tenant funds its own bank from the
      ;; console, so this route alone drops to the org tier (accrue /
      ;; capitalize stay admin-only). The handler holds the tenant
      ;; boundary; `admin` joins `org`, carrying no bank of its own.
      {:openapi {:security ^:replace [{"bearerAuth" ["org" "admin"]}]}
       :post {:summary "Simulate an inbound transfer"
              :openapi {:operationId "SimulateInboundTransfer"
                        :requestBody {:required true}
                        :parameters ^:replace
                                    [shared.parameters/ref-bank-id
                                     shared.parameters/ref-idempotency-key]}
              :parameters {:body [:ref "SimulateInboundTransferRequest"]}
              :interceptors [server/require-idempotency-key
                             bank-idempotency/cache-response]
              :responses {200 {:body [:ref
                                      "SimulateInboundTransferResponse"]}
                          404 (ErrorResponse [#'BankNotFound
                                              #'BalanceNotFound])
                          422 (ErrorResponse [#'TransactionAlreadyRecorded
                                              #'InvalidAmount])}
              :handler handlers/inbound-transfer}}]
     ["/accrue"
      {:post {:summary "Accrue interest"
              :openapi {:operationId "SimulateAccrue"
                        :requestBody {:required true}
                        :parameters ^:replace
                                    [shared.parameters/ref-bank-id
                                     shared.parameters/ref-idempotency-key]}
              :parameters {:body [:ref "SimulateInterestRequest"]}
              :interceptors [server/require-idempotency-key
                             bank-idempotency/cache-response]
              :responses {200 {:body [:ref
                                      "SimulateInterestResponse"]}
                          404 (ErrorResponse [#'BankNotFound
                                              #'SettlementAccountNotFound])
                          422 (ErrorResponse [#'TransactionAlreadyRecorded])}
              :handler handlers/accrue}}]
     ["/capitalize"
      {:post {:summary "Capitalize interest"
              :openapi {:operationId "SimulateCapitalize"
                        :requestBody {:required true}
                        :parameters ^:replace
                                    [shared.parameters/ref-bank-id
                                     shared.parameters/ref-idempotency-key]}
              :parameters {:body [:ref "SimulateInterestRequest"]}
              :interceptors [server/require-idempotency-key
                             bank-idempotency/cache-response]
              :responses {200 {:body [:ref
                                      "SimulateInterestResponse"]}
                          404 (ErrorResponse [#'BankNotFound
                                              #'SettlementAccountNotFound])
                          422 (ErrorResponse [#'TransactionAlreadyRecorded])}
              :handler handlers/capitalize}}]]]])
