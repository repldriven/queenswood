(ns com.repldriven.queenswood.api.balance.routes
  (:require
    [com.repldriven.queenswood.api.balance.examples :refer [BalanceNotFound]]
    [com.repldriven.queenswood.api.balance.queries :as queries]

    [com.repldriven.queenswood.api.shared.parameters :as shared.parameters]

    [com.repldriven.queenswood.api-schema.interface :refer [ErrorResponse]]
    [com.repldriven.queenswood.cash-account-api.interface :refer
     [CashAccountNotFound]]))

(def routes
  [["/cash-accounts/{account-id}/balances"
    {:openapi {:tags ["Balances"]
               :security [{"bearerAuth" ["org:viewer"]}]
               :parameters [shared.parameters/ref-bank-id-header]}
     :parameters {:path {:account-id [:ref "CashAccountId"]}}}
    [""
     {:get {:summary "List a cash account's balances"
            :openapi {:operationId "ListBalances"
                      :description
                      (str
                       "Every balance bucket the account holds, one per type, "
                       "currency and status, with its credit and debit totals, "
                       "and the posted and available balances derived from "
                       "them.")}
            :responses {200 {:description "The account's balances."
                             :body [:ref "BalanceList"]}
                        404 (ErrorResponse [#'CashAccountNotFound])}
            :handler queries/list-balances}}]
    ["/{balance-type}/{currency}/{balance-status}"
     {:get {:summary "Retrieve a balance"
            :openapi {:operationId "RetrieveBalance"
                      :description
                      (str
                       "The balance bucket for the type, currency and status in"
                       " the path. Returns 404 when the account holds no such "
                       "bucket.")}
            :parameters {:path {:balance-type [:ref "BalanceType"]
                                :currency [:ref "Currency"]
                                :balance-status [:ref "BalanceStatus"]}}
            :responses {200 {:description "The balance." :body [:ref "Balance"]}
                        404 (ErrorResponse [#'CashAccountNotFound
                                            #'BalanceNotFound])}
            :handler queries/get-balance}}]]])
