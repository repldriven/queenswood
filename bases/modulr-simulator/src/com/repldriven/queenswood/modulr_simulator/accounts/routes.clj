(ns com.repldriven.queenswood.modulr-simulator.accounts.routes
  (:require
    [com.repldriven.queenswood.modulr-simulator.accounts.handlers :as
     handlers]))

(def ^:private account-path [:map [:accountId string?]])

(def routes
  [["/customers/{customerId}/accounts"
    {:openapi {:tags ["Accounts"]}
     :post {:summary "Create an account for a customer"
            :openapi {:operationId "CreateAccount"}
            :parameters {:path [:map [:customerId string?]]
                         :body [:ref "CreateAccountRequest"]}
            :responses {201 {:body [:ref "Account"]}
                        400 {:body [:ref "Problem"]}}
            :handler handlers/create}}]
   ["/accounts/{accountId}"
    {:openapi {:tags ["Accounts"]}
     :get {:summary "Retrieve an account"
           :openapi {:operationId "GetAccount"}
           :parameters {:path account-path}
           :responses {200 {:body [:ref "Account"]}}
           :handler handlers/fetch}}]
   ["/accounts/{accountId}/block"
    {:openapi {:tags ["Accounts"]}
     :post {:summary "Block an account"
            :openapi {:operationId "BlockAccount"}
            :parameters {:path account-path}
            :responses {204 {:description "Blocked."}}
            :handler handlers/block}}]
   ["/accounts/{accountId}/unblock"
    {:openapi {:tags ["Accounts"]}
     :post {:summary "Unblock an account"
            :openapi {:operationId "UnblockAccount"}
            :parameters {:path account-path}
            :responses {204 {:description "Unblocked."}}
            :handler handlers/unblock}}]
   ["/accounts/{accountId}/close"
    {:openapi {:tags ["Accounts"]}
     :post {:summary "Close an account whose balance is zero"
            :openapi {:operationId "CloseAccount"}
            :parameters {:path account-path}
            :responses {204 {:description "Closed."}
                        400 {:body [:ref "Problem"]}}
            :handler handlers/close}}]])
