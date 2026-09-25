(ns com.repldriven.queenswood.api.ledger-account.routes
  (:require
    [com.repldriven.queenswood.api.ledger-account.queries :as queries]

    [com.repldriven.queenswood.api.shared.parameters :as shared.parameters]

    [com.repldriven.queenswood.api-schema.interface :refer [ErrorResponse]]
    [com.repldriven.queenswood.ledger-account-api.interface :refer
     [LedgerAccountNotFound]]))

(def routes
  [["/ledger-accounts"
    {:openapi {:tags ["Ledger Accounts"]
               :security [{"bearerAuth" ["org:viewer"]}]
               :parameters [shared.parameters/ref-bank-id-header]}}
    [""
     {:get {:summary "List ledger accounts"
            :openapi {:operationId "ListLedgerAccounts"
                      :description
                      (str "The general ledger accounts of the bank the "
                           "`Bank-Id` header names, each with its posted "
                           "balance, and a trial balance per currency "
                           "totalling their debits against their credits.")}
            :responses {200 {:description
                             "The bank's ledger accounts and its trial balance."
                             :body [:ref "LedgerAccountList"]}}
            :handler queries/list-ledger-accounts}}]
    ["/{account-id}"
     {:parameters {:path {:account-id [:ref "LedgerAccountId"]}}}
     [""
      {:get {:summary "Retrieve a ledger account"
             :openapi {:operationId "RetrieveLedgerAccount"
                       :description
                       (str
                        "The ledger account, without its posted balance. List "
                        "its balances for that.")}
             :responses {200 {:description "The ledger account."
                              :body [:ref "LedgerAccount"]}
                         404 (ErrorResponse [#'LedgerAccountNotFound])}
             :handler queries/get-ledger-account}}]
     ["/balances"
      {:get {:summary "List a ledger account's balances"
             :openapi
             {:operationId "ListLedgerAccountBalances"
              :description
              (str "Every balance bucket the ledger account holds, with its"
                   " credit and debit totals, and the posted and available "
                   "balances derived from them.")}
             :responses {200 {:description "The ledger account's balances."
                              :body [:ref "LedgerBalanceList"]}
                         404 (ErrorResponse [#'LedgerAccountNotFound])}
             :handler queries/list-balances}}]]]])