(ns com.repldriven.queenswood.api.bank.routes
  (:require
    [com.repldriven.queenswood.api.bank.commands :as bank-commands]
    [com.repldriven.queenswood.api.bank.examples :refer
     [BankLimitExceeded BankNotFound BankInvalidStatus BankUnknownTier]]
    [com.repldriven.queenswood.api.bank.queries :as queries]

    [com.repldriven.queenswood.api.schema :refer [ErrorResponse]]
    [com.repldriven.queenswood.api.shared.idempotency :as shared.idempotency]
    [com.repldriven.queenswood.api.shared.parameters :as shared.parameters]

    [com.repldriven.queenswood.idempotency.interface :as bank-idempotency]

    [com.repldriven.mono.server.interface :as server]))

(def routes
  [["/banks"
    {:openapi {:tags ["Banks"] :security [{"bearerAuth" ["admin"]}]}}
    [""
     {:get {:summary "Retrieve banks"
            :openapi {:operationId "RetrieveBanks"}
            :responses {200 {:body [:ref "BankList"]}}
            :handler queries/list-banks}
      :post {:summary "Create a new bank"
             :openapi {:operationId "CreateBank"
                       :requestBody {:required true}
                       :parameters ^:replace
                                   [shared.parameters/ref-idempotency-key]}
             :interceptors [server/require-idempotency-key
                            bank-idempotency/cache-response]
             :parameters {:body [:ref "CreateBankRequest"]}
             :responses (shared.idempotency/with-responses
                         {201 {:body [:ref "CreateBankResponse"]}
                          422 (ErrorResponse [#'BankLimitExceeded])})
             :handler bank-commands/create-bank}}]
    ["/{bank-id}"
     {:parameters {:path {:bank-id [:ref "BankId"]}}}
     ["/change-tier"
      {:post {:summary "Change a bank's tier"
              :openapi {:operationId "ChangeBankTier"
                        :requestBody {:required true}
                        :parameters ^:replace [shared.parameters/ref-bank-id]}
              :parameters {:body [:ref "ChangeBankTierRequest"]}
              :responses {200 {:body [:ref "ChangeBankTierResponse"]}
                          404 (ErrorResponse [#'BankNotFound])
                          409 (ErrorResponse [#'BankInvalidStatus])
                          422 (ErrorResponse [#'BankUnknownTier])}
              :handler bank-commands/change-bank-tier}}]
     ["/change-status"
      {:post {:summary "Change a bank's status between test and live"
              :openapi {:operationId "ChangeBankStatus"
                        :requestBody {:required true}
                        :parameters ^:replace [shared.parameters/ref-bank-id]}
              :parameters {:body [:ref "ChangeBankStatusRequest"]}
              :responses {200 {:body [:ref "ChangeBankStatusResponse"]}
                          404 (ErrorResponse [#'BankNotFound])
                          409 (ErrorResponse [#'BankInvalidStatus])}
              :handler bank-commands/change-bank-status}}]]]])
