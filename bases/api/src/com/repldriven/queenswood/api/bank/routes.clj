(ns com.repldriven.queenswood.api.bank.routes
  (:require
    [com.repldriven.queenswood.api.bank.commands :as bank-commands]
    [com.repldriven.queenswood.api.bank.queries :as queries]

    [com.repldriven.queenswood.api.shared.headers :as shared.headers]
    [com.repldriven.queenswood.api.shared.idempotency :as shared.idempotency]
    [com.repldriven.queenswood.api.shared.interceptors :as shared.interceptors]
    [com.repldriven.queenswood.api.shared.parameters :as shared.parameters]

    [com.repldriven.queenswood.api-schema.interface :as api-schema :refer
     [ErrorExamples ErrorResponse]]
    [com.repldriven.queenswood.bank-api.interface :refer
     [BankInvalidStatus BankNotFound BankUnknownTier BankUnnamed
      CompanyNotActive CompanyRequired IdvUnsupportedCriteria
      OperatorFieldRefused]]
    [com.repldriven.queenswood.company-api.interface :as company-api]
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
       :openapi
       {:operationId "CreateBank"
        :security ^:replace [{"bearerAuth" ["admin"]} {"bearerAuth" ["user"]}]
        :description (str "Creates the bank with its party, its ledger and "
                          "an own-funds cash account in each currency, bound "
                          "to the tier's policies and to the company "
                          "`company-number` names, looked up in the company "
                          "registry. A signed-in person must name a company, "
                          "gets a test bank on the micro tier in GBP, and "
                          "becomes its owner; naming a status, tier, "
                          "currencies or owner is refused with 403, and naming"
                          " no company with 422. An unknown company returns "
                          "404, and one that is not active is refused with "
                          "422, as is a tier with no policies and one "
                          "requiring a verification or screening the "
                          "identity provider does not establish. The response "
                          "carries the bank's client secret, returned only "
                          "here, the person's owner membership, and with "
                          "`owner-email` the owner invitation emailed to that "
                          "address.")
        :requestBody {:required true}
        :parameters ^:replace [shared.parameters/ref-idempotency-key]}
       :interceptors [server/require-idempotency-key
                      bank-idempotency/cache-response]
       :parameters {:body [:ref "CreateBankRequest"]}
       :responses
       (shared.idempotency/with-responses
        {201 {:description "The created bank and its client secret."
              :body [:ref "CreateBankResponse"]
              :openapi {:headers {"Location" (shared.headers/location "bank")}}}
         403 (ErrorExamples [#'api-schema/PolicyDenied
                             #'OperatorFieldRefused])
         404 (ErrorResponse [#'company-api/CompanyNotFound])
         422 (ErrorResponse [#'BankUnknownTier #'CompanyNotActive
                             #'CompanyRequired #'IdvUnsupportedCriteria])
         503 (ErrorExamples [#'company-api/CompanyRegistryUnavailable])})
       :handler bank-commands/create-bank}}]]
   ["/bank"
    ;; The bank the `Bank-Id` header names: a member's own, or any an
    ;; operator names. `named-bank` refuses an operator who names none.
    {:openapi {:tags ["Banks"]} :interceptors [shared.interceptors/named-bank]}
    [""
     {:get {:summary "Retrieve the bank"
            :openapi {:operationId "RetrieveBank"
                      :security [{"bearerAuth" ["org:viewer"]}
                                 {"bearerAuth" ["admin"]}]
                      :description
                      (str "The bank the `Bank-Id` header names, with its "
                           "party, its cash accounts and their balances, its"
                           " tier and its active owners, as the bank list "
                           "shows it. An operator naming no bank is refused "
                           "with 403.")
                      :parameters ^:replace
                                  [shared.parameters/ref-bank-id-header]}
            :responses {200 {:description "The bank." :body [:ref "Bank"]}
                        403 (ErrorExamples [#'BankUnnamed])
                        404 (ErrorResponse [#'BankNotFound])}
            :handler queries/get-bank}}]
    ["/change-tier"
     {:post {:summary "Change the bank's tier"
             :openapi
             {:operationId "ChangeBankTier"
              :security [{"bearerAuth" ["admin"]}]
              :description
              (str "Binds the bank the `Bank-Id` header names to the named "
                   "tier's policies in place of its current tier's, and "
                   "returns the bank. A bank that is neither test nor live "
                   "is refused with 409. A tier with no policies is refused "
                   "with 422, as is one requiring a verification or "
                   "screening the identity provider does not establish. "
                   "Naming no bank is refused with 403.")
              :requestBody {:required true}
              :parameters ^:replace [shared.parameters/ref-bank-id-header]}
             :parameters {:body [:ref "ChangeBankTierRequest"]}
             :responses {200 {:description "The bank with its new tier."
                              :body [:ref "ChangeBankTierResponse"]}
                         403 (ErrorExamples [#'BankUnnamed])
                         404 (ErrorResponse [#'BankNotFound])
                         409 (ErrorResponse [#'BankInvalidStatus])
                         422 (ErrorResponse [#'BankUnknownTier
                                             #'IdvUnsupportedCriteria])}
             :handler bank-commands/change-bank-tier}}]
    ["/change-status"
     {:post {:summary "Change the bank's status"
             :openapi {:operationId "ChangeBankStatus"
                       :security [{"bearerAuth" ["admin"]}]
                       :description
                       (str "Moves the bank the `Bank-Id` header names "
                            "between test and live, and tokens issued to its"
                            " client afterwards carry the new status's "
                            "audience. A bank that is neither test nor live,"
                            " or already has the requested status, is "
                            "refused with 409. Naming no bank is refused "
                            "with 403. Returns the bank.")
                       :requestBody {:required true}
                       :parameters ^:replace
                                   [shared.parameters/ref-bank-id-header]}
             :parameters {:body [:ref "ChangeBankStatusRequest"]}
             :responses {200 {:description "The bank with its new status."
                              :body [:ref "ChangeBankStatusResponse"]}
                         403 (ErrorExamples [#'BankUnnamed])
                         404 (ErrorResponse [#'BankNotFound])
                         409 (ErrorResponse [#'BankInvalidStatus])}
             :handler bank-commands/change-bank-status}}]]])
