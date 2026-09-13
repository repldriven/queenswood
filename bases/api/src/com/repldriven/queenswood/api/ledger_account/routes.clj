(ns com.repldriven.queenswood.api.ledger-account.routes
  (:require
    [com.repldriven.queenswood.api.ledger-account.examples :refer
     [LedgerAccountNotFound]]
    [com.repldriven.queenswood.api.ledger-account.queries :as queries]

    [com.repldriven.queenswood.api.shared.parameters :as shared.parameters]

    [com.repldriven.queenswood.api-schema.interface :refer [ErrorResponse]]))

(def routes
  [["/ledger-accounts"
    {:openapi {:tags ["Ledger Accounts"]
               :security [{"bearerAuth" ["org:viewer"]}]
               :parameters [shared.parameters/ref-bank-id-header]}}
    [""
     {:get {:summary "Retrieve ledger accounts"
            :openapi {:operationId "RetrieveLedgerAccounts"}
            :responses {200 {:body [:ref "LedgerAccountList"]}}
            :handler queries/list-ledger-accounts}}]
    ["/{account-id}"
     {:parameters {:path {:account-id [:ref "LedgerAccountId"]}}}
     [""
      {:get {:summary "Retrieve a ledger account"
             :openapi {:operationId "RetrieveLedgerAccount"}
             :responses {200 {:body [:ref "LedgerAccount"]}
                         404 (ErrorResponse [#'LedgerAccountNotFound])}
             :handler queries/get-ledger-account}}]
     ["/balances"
      {:get {:summary "Retrieve ledger account balances"
             :openapi {:operationId "RetrieveLedgerAccountBalances"}
             :responses {200 {:body [:ref "LedgerBalanceList"]}
                         404 (ErrorResponse [#'LedgerAccountNotFound])}
             :handler queries/list-balances}}]]]])