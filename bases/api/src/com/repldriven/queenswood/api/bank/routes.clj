(ns com.repldriven.queenswood.api.bank.routes
  (:require
    [com.repldriven.queenswood.api.bank.commands :as bank-commands]
    [com.repldriven.queenswood.api.bank.examples :refer
     [BankNotFound BankInvalidStatus BankUnknownTier ForeignBankRead]]
    [com.repldriven.queenswood.api.bank.queries :as queries]
    [com.repldriven.queenswood.api.examples :as api.examples]

    [com.repldriven.queenswood.api.shared.headers :as shared.headers]
    [com.repldriven.queenswood.api.shared.idempotency :as shared.idempotency]
    [com.repldriven.queenswood.api.shared.parameters :as shared.parameters]

    [com.repldriven.queenswood.api-schema.interface :refer
     [ErrorExamples ErrorResponse]]
    [com.repldriven.queenswood.idempotency.interface :as bank-idempotency]

    [com.repldriven.mono.server.interface :as server]))

(def routes
  [["/banks"
    {:openapi {:tags ["Banks"] :security [{"bearerAuth" ["admin"]}]}}
    [""
     {:get {:summary "List banks"
            :openapi {:operationId "ListBanks"
                      :description
                      (str "The banks on the platform, newest first, a page "
                           "at a time, each with its party, its cash accounts "
                           "and their balances, its tier and its active "
                           "owners.")
                      :parameters ^:replace [shared.parameters/ref-page]}
            :parameters {:query shared.parameters/page-query}
            :responses {200 {:description "A page of the platform's banks."
                             :body [:ref "BankList"]}}
            :handler queries/list-banks}
      :post
      {:summary "Create a bank"
       :openapi {:operationId "CreateBank"
                 :description
                 (str "Creates the bank with its party, its ledger and "
                      "an own-funds cash account in each currency "
                      "named, bound to the tier's policies. A tier with "
                      "no policies is refused with 422. The response "
                      "carries the bank's client secret, returned only "
                      "here, and with `owner-email` the owner "
                      "invitation emailed to that address.")
                 :requestBody {:required true}
                 :parameters ^:replace [shared.parameters/ref-idempotency-key]}
       :interceptors [server/require-idempotency-key
                      bank-idempotency/cache-response]
       :parameters {:body [:ref "CreateBankRequest"]}
       :responses (shared.idempotency/with-responses
                   {201 {:description "The created bank and its client secret."
                         :body [:ref "CreateBankResponse"]
                         :openapi {:headers {"Location" (shared.headers/location
                                                         "bank")}}}
                    403 (ErrorExamples [#'api.examples/PolicyDenied])
                    422 (ErrorResponse [#'BankUnknownTier])})
       :handler bank-commands/create-bank}}]
    ["/{bank-id}"
     {:parameters {:path {:bank-id [:ref "BankId"]}}}
     [""
      ;; A bank reads its own record as well as an operator reads any:
      ;; the handler holds the tenant boundary, `admin` joining the level.
      {:openapi {:security ^:replace
                           [{"bearerAuth" ["org:viewer"]}
                            {"bearerAuth" ["admin"]}]}
       :get {:summary "Retrieve a bank"
             :openapi {:operationId "RetrieveBank"
                       :description
                       (str
                        "The bank with its party, its cash accounts and their "
                        "balances, its tier and its active owners, as the bank "
                        "list shows it. A member can retrieve only their own "
                        "bank; another is refused with 403.")
                       :parameters ^:replace
                                   [shared.parameters/ref-bank-id
                                    shared.parameters/ref-bank-id-header]}
             :responses {200 {:description "The bank." :body [:ref "Bank"]}
                         403 (ErrorExamples [#'ForeignBankRead])
                         404 (ErrorResponse [#'BankNotFound])}
             :handler queries/get-bank}}]
     ["/change-tier"
      {:post {:summary "Change a bank's tier"
              :openapi
              {:operationId "ChangeBankTier"
               :description
               (str "Binds the bank to the named tier's policies in place of"
                    " its current tier's, and returns the bank. A bank that "
                    "is neither test nor live is refused with 409. A tier "
                    "with no policies is refused with 422.")
               :requestBody {:required true}
               :parameters ^:replace [shared.parameters/ref-bank-id]}
              :parameters {:body [:ref "ChangeBankTierRequest"]}
              :responses {200 {:description "The bank with its new tier."
                               :body [:ref "ChangeBankTierResponse"]}
                          404 (ErrorResponse [#'BankNotFound])
                          409 (ErrorResponse [#'BankInvalidStatus])
                          422 (ErrorResponse [#'BankUnknownTier])}
              :handler bank-commands/change-bank-tier}}]
     ["/change-status"
      {:post {:summary "Change a bank's status"
              :openapi {:operationId "ChangeBankStatus"
                        :description
                        (str "Moves the bank between test and live, and "
                             "tokens issued to its client afterwards carry "
                             "the new status's audience. A bank that is "
                             "neither test nor live, or already has the "
                             "requested status, is refused with 409. Returns "
                             "the bank.")
                        :requestBody {:required true}
                        :parameters ^:replace [shared.parameters/ref-bank-id]}
              :parameters {:body [:ref "ChangeBankStatusRequest"]}
              :responses {200 {:description "The bank with its new status."
                               :body [:ref "ChangeBankStatusResponse"]}
                          404 (ErrorResponse [#'BankNotFound])
                          409 (ErrorResponse [#'BankInvalidStatus])}
              :handler bank-commands/change-bank-status}}]]]])
