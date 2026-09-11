(ns com.repldriven.queenswood.api.balance.routes
  (:require
    [com.repldriven.queenswood.api.balance.examples :refer [BalanceNotFound]]
    [com.repldriven.queenswood.api.balance.queries :as queries]

    [com.repldriven.queenswood.api.cash-account.examples :refer
     [CashAccountNotFound]]

    [com.repldriven.queenswood.api-schema.interface :refer [ErrorResponse]]))

(def routes
  [["/cash-accounts/{account-id}/balances"
    {:openapi {:tags ["Balances"] :security [{"bearerAuth" ["org"]}]}
     :parameters {:path {:account-id [:ref "CashAccountId"]}}}
    [""
     {:get {:summary "Retrieve account balances"
            :openapi {:operationId "RetrieveBalances"}
            :responses {200 {:body [:ref "BalanceList"]}
                        404 (ErrorResponse [#'CashAccountNotFound])}
            :handler queries/list-balances}}]
    ["/{balance-type}/{currency}/{balance-status}"
     {:get {:summary "Retrieve a balance"
            :openapi {:operationId "RetrieveBalance"}
            :parameters {:path {:balance-type [:ref "BalanceType"]
                                :currency [:ref "Currency"]
                                :balance-status [:ref "BalanceStatus"]}}
            :responses {200 {:body [:ref "Balance"]}
                        404 (ErrorResponse [#'CashAccountNotFound
                                            #'BalanceNotFound])}
            :handler queries/get-balance}}]]])
