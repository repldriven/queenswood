(ns com.repldriven.queenswood.form3-simulator.accounts.routes
  (:require
    [com.repldriven.queenswood.form3-simulator.accounts.handlers
     :as handlers]))

(def routes
  [["/organisation/accounts"
    {:openapi {:tags ["Accounts"]}
     :post {:summary "Register an account for inbound routing"
            :openapi {:operationId "CreateAccount"}
            :parameters {:body [:ref "ResourceRequest"]}
            :responses {201 {:body [:ref "ResourceResponse"]}
                        400 {:body [:ref "ApiError"]}
                        409 {:body [:ref "ApiError"]}}
            :handler handlers/register}}]
   ["/organisation/accounts/{id}"
    {:openapi {:tags ["Accounts"]}
     :get {:summary "Fetch an account"
           :openapi {:operationId "GetAccount"}
           :parameters {:path [:map [:id string?]]}
           :responses {200 {:body [:ref "ResourceResponse"]}
                       404 {:body [:ref "ApiError"]}}
           :handler handlers/fetch}
     :patch {:summary "Close an account"
             :openapi {:operationId "PatchAccount"}
             :parameters {:path [:map [:id string?]]
                          :body [:ref "ResourceRequest"]}
             :responses {200 {:body [:ref "ResourceResponse"]}
                         400 {:body [:ref "ApiError"]}
                         404 {:body [:ref "ApiError"]}}
             :handler handlers/amend}}]])
