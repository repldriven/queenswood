(ns com.repldriven.queenswood.cash-account-api.links
  "OpenAPI 3 `links` objects for cash-account responses. Consumed by
  schemathesis for stateful test generation and rendered by Scalar as
  clickable workflow maps.

  JSON-pointer values reference the response body using the
  kebab-case key shape we emit on the wire.")

(def from-account
  {"GetAccount" {:operationId "RetrieveCashAccount"
                 :parameters {"account-id" "$response.body#/account-id"}}
   "GetBalances" {:operationId "RetrieveBalances"
                  :parameters {"account-id" "$response.body#/account-id"}}
   "GetTransactions" {:operationId "RetrieveAccountTransactions"
                      :parameters {"account-id" "$response.body#/account-id"}}})
