(ns com.repldriven.queenswood.api.cash-account.routes
  (:require
    [com.repldriven.queenswood.api.cash-account.commands :as commands]
    [com.repldriven.queenswood.api.cash-account.examples :refer
     [CashAccountNotFound ProductNotPublished InvalidCurrency PartyNotFound
      ProductNotFound CashAccountInvalidStatus CashAccountNonZeroBalance]]
    [com.repldriven.queenswood.api.cash-account.links :as links]
    [com.repldriven.queenswood.api.cash-account.queries :as queries]

    [com.repldriven.queenswood.api.schema :refer [ErrorResponse]]
    [com.repldriven.queenswood.api.shared.idempotency :as shared.idempotency]
    [com.repldriven.queenswood.api.shared.parameters :as shared.parameters]

    [com.repldriven.queenswood.idempotency.interface :as bank-idempotency]

    [com.repldriven.mono.server.interface :as server]))

(def ^:private list-cash-accounts-query-schema
  [:map {:closed true}
   [:embed {:optional true} [:ref "EmbedQuery"]]
   [:page {:optional true} [:ref "PageQuery"]]])

(def ^:private get-cash-account-query-schema
  [:map {:closed true}
   [:embed {:optional true} [:ref "EmbedQuery"]]])

(def routes
  [["/cash-accounts"
    {:openapi {:tags ["Cash Accounts"] :security [{"bearerAuth" ["org"]}]}}
    [""
     {:get {:summary "Retrieve cash accounts"
            :openapi {:operationId "RetrieveCashAccounts"
                      :parameters ^:replace
                                  [shared.parameters/ref-page
                                   shared.parameters/ref-embed]}
            :parameters {:query list-cash-accounts-query-schema}
            :responses {200 {:body [:ref "CashAccountList"]}}
            :handler queries/list-cash-accounts}
      :post {:summary "Open a new cash account"
             :openapi {:operationId "CreateCashAccount"
                       :requestBody {:required true}
                       :parameters ^:replace
                                   [shared.parameters/ref-idempotency-key]}
             :interceptors [server/require-idempotency-key
                            bank-idempotency/cache-response]
             :parameters {:body [:ref "CreateCashAccountRequest"]}
             :responses (shared.idempotency/with-responses
                         {200 {:body [:ref "CreateCashAccountResponse"]
                               :openapi {:links links/from-account}}
                          404 (ErrorResponse [#'PartyNotFound
                                              #'ProductNotFound])
                          422 (ErrorResponse [#'ProductNotPublished
                                              #'InvalidCurrency])})
             :handler commands/open-cash-account}}]
    ["/{account-id}" {:parameters {:path {:account-id [:ref "CashAccountId"]}}}
     [""
      {:get {:summary "Retrieve a cash account"
             :openapi {:operationId "RetrieveCashAccount"
                       :parameters ^:replace
                                   [shared.parameters/ref-account-id
                                    shared.parameters/ref-embed]}
             :parameters {:query get-cash-account-query-schema}
             :responses {200 {:body [:ref "CashAccount"]}
                         404 (ErrorResponse [#'CashAccountNotFound])}
             :handler queries/get-cash-account}}]
     ["/transactions"
      {:get {:summary "Retrieve account transactions"
             :openapi {:operationId "RetrieveAccountTransactions"}
             :responses {200 {:body [:ref "TransactionList"]}
                         404 (ErrorResponse [#'CashAccountNotFound])}
             :handler queries/list-transactions}}]
     ["/close"
      {:post {:summary "Close a cash account"
              :openapi {:operationId "CloseCashAccount"
                        :parameters ^:replace
                                    [shared.parameters/ref-account-id
                                     shared.parameters/ref-idempotency-key]}
              :interceptors [server/require-idempotency-key
                             bank-idempotency/cache-response]
              :responses (shared.idempotency/with-responses
                          {200 {:body [:ref "CloseCashAccountResponse"]
                                :openapi {:links links/from-account}}
                           404 (ErrorResponse [#'CashAccountNotFound])
                           409 (ErrorResponse [#'CashAccountInvalidStatus
                                               #'CashAccountNonZeroBalance])})
              :handler commands/close-cash-account}}]
     ["/suspend"
      {:post {:summary "Suspend a cash account"
              :openapi {:operationId "SuspendCashAccount"
                        :parameters ^:replace
                                    [shared.parameters/ref-account-id
                                     shared.parameters/ref-idempotency-key]}
              :interceptors [server/require-idempotency-key
                             bank-idempotency/cache-response]
              :responses (shared.idempotency/with-responses
                          {200 {:body [:ref "SuspendCashAccountResponse"]
                                :openapi {:links links/from-account}}
                           404 (ErrorResponse [#'CashAccountNotFound])
                           409 (ErrorResponse [#'CashAccountInvalidStatus])})
              :handler commands/suspend-cash-account}}]
     ["/resume"
      {:post {:summary "Resume a suspended cash account"
              :openapi {:operationId "ResumeCashAccount"
                        :parameters ^:replace
                                    [shared.parameters/ref-account-id
                                     shared.parameters/ref-idempotency-key]}
              :interceptors [server/require-idempotency-key
                             bank-idempotency/cache-response]
              :responses (shared.idempotency/with-responses
                          {200 {:body [:ref "ResumeCashAccountResponse"]
                                :openapi {:links links/from-account}}
                           404 (ErrorResponse [#'CashAccountNotFound])
                           409 (ErrorResponse [#'CashAccountInvalidStatus])})
              :handler commands/resume-cash-account}}]
     ["/rotate-address"
      {:post {:summary "Rotate a cash account's payment address"
              :openapi {:operationId "RotateCashAccountAddress"
                        :parameters ^:replace
                                    [shared.parameters/ref-account-id
                                     shared.parameters/ref-idempotency-key]}
              :interceptors [server/require-idempotency-key
                             bank-idempotency/cache-response]
              :responses (shared.idempotency/with-responses
                          {200 {:body [:ref "RotateCashAccountAddressResponse"]
                                :openapi {:links links/from-account}}
                           404 (ErrorResponse [#'CashAccountNotFound])
                           409 (ErrorResponse [#'CashAccountInvalidStatus])})
              :handler commands/rotate-cash-account-address}}]]]])
