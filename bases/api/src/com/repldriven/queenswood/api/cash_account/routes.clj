(ns com.repldriven.queenswood.api.cash-account.routes
  (:require
    [com.repldriven.queenswood.api.cash-account.commands :as commands]
    [com.repldriven.queenswood.api.cash-account.queries :as queries]

    [com.repldriven.queenswood.api.shared.headers :as shared.headers]
    [com.repldriven.queenswood.api.shared.idempotency :as shared.idempotency]
    [com.repldriven.queenswood.api.shared.parameters :as shared.parameters]

    [com.repldriven.queenswood.api-schema.interface :as api-schema :refer
     [ErrorExamples ErrorResponse]]
    [com.repldriven.queenswood.cash-account-api.interface :as cash-account-api
     :refer
     [CashAccountEarlyClosureRefused CashAccountInvalidPayoutAccount
      CashAccountInvalidStatus CashAccountNonZeroBalance CashAccountNotFound
      InvalidCurrency PartyNotFound ProductNotFound ProductNotPublished]]
    [com.repldriven.queenswood.idempotency.interface :as bank-idempotency]

    [com.repldriven.mono.server.interface :as server]))

(def ^:private list-cash-accounts-query-schema
  [:map {:closed true}
   [:embed {:optional true} [:ref "EmbedQuery"]]
   [:page {:optional true} [:ref "PageQuery"]]])

(def ^:private get-cash-account-query-schema
  [:map {:closed true}
   [:embed {:optional true} [:ref "EmbedQuery"]]])

(def ^:private optional-close [:maybe [:ref "CloseCashAccountRequest"]])

;; `:maybe` lets the body be left out; the document says so with
;; `required: false` and names the schema, rather than a null branch.
(def ^:private optional-close-request-body
  {:required false
   :content ^:replace
            {"application/json"
             {:schema {:$ref "#/components/schemas/CloseCashAccountRequest"}}}})

(def routes
  [["/cash-accounts"
    {:openapi {:tags ["Cash Accounts"]}}
    [""
     {:get {:summary "List cash accounts"
            :openapi {:operationId "ListCashAccounts"
                      :description
                      (str "The cash accounts of the bank the `Bank-Id` "
                           "header names, a page at a time. Set "
                           "`embed[balances]`, `embed[transactions]` or "
                           "`embed[operations]` to include each account's "
                           "balances, transactions or latest operations.")
                      :security [{"bearerAuth" ["org:viewer"]}]
                      :parameters ^:replace
                                  [shared.parameters/ref-page
                                   shared.parameters/ref-embed
                                   shared.parameters/ref-bank-id-header]}
            :parameters {:query list-cash-accounts-query-schema}
            :responses {200 {:description
                             "One page of the bank's cash accounts."
                             :body [:ref "CashAccountList"]}}
            :handler queries/list-cash-accounts}
      :post
      {:summary "Open a cash account"
       :openapi {:operationId "CreateCashAccount"
                 :description
                 (str "The party must be active, and the product must have a "
                      "version in effect today that allows the currency; "
                      "otherwise the request is refused with 422. Returns the "
                      "account in the `opening` status. A `cash-"
                      "account.opened` webhook notification follows once it is"
                      " opened.")
                 :security [{"bearerAuth" ["org:developer"]}]
                 :requestBody {:required true}
                 :parameters ^:replace
                             [shared.parameters/ref-bank-id-header
                              shared.parameters/ref-idempotency-key]}
       :interceptors [server/require-idempotency-key
                      bank-idempotency/cache-response]
       :parameters {:body [:ref "CreateCashAccountRequest"]}
       :responses (shared.idempotency/with-responses
                   {201 {:description "The account in the `opening` status."
                         :body [:ref "CreateCashAccountResponse"]
                         :openapi {:headers {"Location" (shared.headers/location
                                                         "cash account")}
                                   :links cash-account-api/from-account}}
                    403 (ErrorExamples [#'api-schema/PolicyDenied])
                    404 (ErrorResponse [#'PartyNotFound
                                        #'ProductNotFound])
                    422 (ErrorResponse [#'ProductNotPublished
                                        #'InvalidCurrency])
                    429 (ErrorResponse [#'api-schema/PolicyLimitExceeded])})
       :handler commands/open-cash-account}}]
    ["/{account-id}" {:parameters {:path {:account-id [:ref "CashAccountId"]}}}
     [""
      {:openapi {:security [{"bearerAuth" ["org:viewer"]}]}
       :get {:summary "Retrieve a cash account"
             :openapi {:operationId "RetrieveCashAccount"
                       :description
                       (str "Set `embed[balances]`, `embed[transactions]` or "
                            "`embed[operations]` to include the account's "
                            "balances, transactions or latest operations.")
                       :parameters ^:replace
                                   [shared.parameters/ref-account-id
                                    shared.parameters/ref-embed
                                    shared.parameters/ref-bank-id-header]}
             :parameters {:query get-cash-account-query-schema}
             :responses {200 {:description "The cash account."
                              :body [:ref "CashAccount"]}
                         404 (ErrorResponse [#'CashAccountNotFound])}
             :handler queries/get-cash-account}}]
     ["/transactions"
      {:openapi {:security [{"bearerAuth" ["org:viewer"]}]}
       :get
       {:summary "List a cash account's transactions"
        :openapi {:operationId "ListAccountTransactions"
                  :description
                  (str "The account's side of each transaction that "
                       "touched it, newest first, a page at a time, "
                       "with the transaction's type, status and " "reference.")
                  :parameters ^:replace
                              [shared.parameters/ref-account-id
                               shared.parameters/ref-page
                               shared.parameters/ref-bank-id-header]}
        :parameters {:query shared.parameters/page-query}
        :responses {200 {:description
                         "A page of the account's transactions, newest first."
                         :body [:ref "TransactionList"]}
                    404 (ErrorResponse [#'CashAccountNotFound])}
        :handler queries/list-transactions}}]
     ["/operations"
      {:openapi {:security [{"bearerAuth" ["org:viewer"]}]}
       :get {:summary "List a cash account's operations"
             :openapi {:operationId "ListCashAccountOperations"
                       :description
                       (str "Each suspend, resume, close or address "
                            "rotation asked of the account, newest first, a "
                            "page at a time, with whether its payment "
                            "provider carried it out.")
                       :parameters ^:replace
                                   [shared.parameters/ref-account-id
                                    shared.parameters/ref-page
                                    shared.parameters/ref-bank-id-header]}
             :parameters {:query shared.parameters/page-query}
             :responses {200
                         {:description
                          "A page of the account's operations, newest first."
                          :body [:ref "CashAccountOperationList"]}
                         404 (ErrorResponse [#'CashAccountNotFound])}
             :handler queries/list-operations}}]
     ["/close"
      {:openapi {:security [{"bearerAuth" ["org:developer"]}]}
       :post
       {:summary "Close a cash account"
        :openapi {:operationId "CloseCashAccount"
                  :description
                  (str "An opened, suspended or closing account can be "
                       "closed. The close settles the interest accrued by the "
                       "version's closure terms, charges an early closure's "
                       "fee, and moves what is left to `payout-account-id`, "
                       "another opened account of the same party and "
                       "currency, or a payout account that is not one is "
                       "refused with 422. A close inside an early window the "
                       "version refuses closures in is refused with 409, and "
                       "one leaving a balance with no payout account to move "
                       "it to, or holding a pending amount, is refused with "
                       "409 unless the bank's policies allow it. A payout "
                       "the payout account's limits refuse is refused with "
                       "429. Returns the account in the `closing` status; it "
                       "becomes `closed` shortly after, once it holds "
                       "nothing, and a `cash-account.closed` webhook "
                       "notification follows. Where the payment provider "
                       "refuses the close, the account returns to the status "
                       "it closed from, its operation is refused with the "
                       "provider's reason, and a `cash-account.close-refused` "
                       "webhook notification follows.")
                  :parameters ^:replace
                              [shared.parameters/ref-account-id
                               shared.parameters/ref-bank-id-header
                               shared.parameters/ref-idempotency-key]
                  :requestBody optional-close-request-body}
        :parameters {:body optional-close}
        :interceptors [server/require-idempotency-key
                       bank-idempotency/cache-response]
        :responses (shared.idempotency/with-responses
                    {200 {:description "The account in the `closing` status."
                          :body [:ref "CloseCashAccountResponse"]
                          :openapi {:links cash-account-api/from-account}}
                     403 (ErrorExamples [#'api-schema/PolicyDenied])
                     404 (ErrorResponse [#'CashAccountNotFound])
                     409 (ErrorResponse [#'CashAccountInvalidStatus
                                         #'CashAccountNonZeroBalance
                                         #'CashAccountEarlyClosureRefused])
                     422 (ErrorResponse [#'CashAccountInvalidPayoutAccount])
                     429 (ErrorResponse [#'api-schema/PolicyLimitExceeded])})
        :handler commands/close-cash-account}}]
     ["/suspend"
      {:openapi {:security [{"bearerAuth" ["org:developer"]}]}
       :post {:summary "Suspend a cash account"
              :openapi {:operationId "SuspendCashAccount"
                        :description
                        (str "Only an opened account can be suspended. While "
                             "suspended it cannot send or receive payments, "
                             "and money arriving for it is parked in "
                             "suspense. A `cash-account.suspended` webhook "
                             "notification follows.")
                        :parameters ^:replace
                                    [shared.parameters/ref-account-id
                                     shared.parameters/ref-bank-id-header
                                     shared.parameters/ref-idempotency-key]}
              :interceptors [server/require-idempotency-key
                             bank-idempotency/cache-response]
              :responses (shared.idempotency/with-responses
                          {200 {:description "The suspended account."
                                :body [:ref "SuspendCashAccountResponse"]
                                :openapi {:links cash-account-api/from-account}}
                           403 (ErrorExamples [#'api-schema/PolicyDenied])
                           404 (ErrorResponse [#'CashAccountNotFound])
                           409 (ErrorResponse [#'CashAccountInvalidStatus])})
              :handler commands/suspend-cash-account}}]
     ["/resume"
      {:openapi {:security [{"bearerAuth" ["org:developer"]}]}
       :post {:summary "Resume a suspended cash account"
              :openapi {:operationId "ResumeCashAccount"
                        :description
                        (str "The account returns to opened. An account that "
                             "is not suspended is refused with 409, and one "
                             "that has taken its operations for the day with "
                             "429. A `cash-account.resumed` webhook "
                             "notification follows.")
                        :parameters ^:replace
                                    [shared.parameters/ref-account-id
                                     shared.parameters/ref-bank-id-header
                                     shared.parameters/ref-idempotency-key]}
              :interceptors [server/require-idempotency-key
                             bank-idempotency/cache-response]
              :responses (shared.idempotency/with-responses
                          {200 {:description "The resumed account."
                                :body [:ref "ResumeCashAccountResponse"]
                                :openapi {:links cash-account-api/from-account}}
                           403 (ErrorExamples [#'api-schema/PolicyDenied])
                           404 (ErrorResponse [#'CashAccountNotFound])
                           409 (ErrorResponse [#'CashAccountInvalidStatus])
                           429 (ErrorResponse
                                [#'api-schema/PolicyLimitExceeded])})
              :handler commands/resume-cash-account}}]
     ["/rotate-address"
      {:openapi {:security [{"bearerAuth" ["org:developer"]}]}
       :post {:summary "Rotate a cash account's payment address"
              :openapi
              {:operationId "RotateCashAccountAddress"
               :description
               (str "Only an opened account can be rotated, and one that has "
                    "taken its operations for the day is refused with 429. It "
                    "is given a new account number under the same sort code, "
                    "and the old one is retired rather than redirected, so "
                    "money sent to it is parked in suspense. A "
                    "`cash-account.address-rotated` webhook notification "
                    "follows. Where the payment provider refuses the reissue, "
                    "the account keeps its address, its operation is refused "
                    "with the provider's reason, and a "
                    "`cash-account.operation-refused` webhook notification "
                    "follows.")
               :parameters ^:replace
                           [shared.parameters/ref-account-id
                            shared.parameters/ref-bank-id-header
                            shared.parameters/ref-idempotency-key]}
              :interceptors [server/require-idempotency-key
                             bank-idempotency/cache-response]
              :responses
              (shared.idempotency/with-responses
               {200 {:description "The account with its new payment address."
                     :body [:ref "RotateCashAccountAddressResponse"]
                     :openapi {:links cash-account-api/from-account}}
                403 (ErrorExamples [#'api-schema/PolicyDenied])
                404 (ErrorResponse [#'CashAccountNotFound])
                409 (ErrorResponse [#'CashAccountInvalidStatus])
                429 (ErrorResponse [#'api-schema/PolicyLimitExceeded])})
              :handler commands/rotate-cash-account-address}}]]]])
