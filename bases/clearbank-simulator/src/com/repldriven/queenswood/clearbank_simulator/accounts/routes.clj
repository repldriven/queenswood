(ns com.repldriven.queenswood.clearbank-simulator.accounts.routes
  (:require
    [com.repldriven.queenswood.clearbank-simulator.accounts.handlers
     :as handlers]))

(def routes
  [["/v1/virtual-accounts"
    {:openapi {:tags ["Accounts"]}}
    [""
     {:post {:summary "Open a virtual account"
             :openapi {:operationId "OpenVirtualAccount"}
             :parameters {:body [:ref "VirtualAccountRequest"]}
             :responses {201 {:body [:ref "VirtualAccount"]}}
             :handler (handlers/create nil)}}]
    ["/{id}/close"
     {:post {:summary "Close a virtual account"
             :openapi {:operationId "CloseVirtualAccount"}
             :parameters {:path [:map [:id string?]]}
             :responses {200 {:body [:map [:id string?]]}}
             :handler (handlers/close nil)}}]
    ["/{id}/reissue"
     {:post {:summary "Move a virtual account to a new account number"
             :openapi {:operationId "ReissueVirtualAccount"}
             :parameters {:path [:map [:id string?]]
                          :body [:ref "VirtualAccountRequest"]}
             :responses {200 {:body [:ref "VirtualAccount"]}}
             :handler (handlers/reissue nil)}}]]
   ["/simulate/open-refused"
    {:openapi {:tags ["Simulate"]}
     :post {:summary "Decline the next account opening"
            :openapi {:operationId "SimulateOpenRefused"}
            :responses {204 {:description "The next opening is declined."}}
            :handler (handlers/refuse-next nil)}}]])
