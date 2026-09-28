(ns com.repldriven.queenswood.form3-adapter.cop.routes
  (:require
    [com.repldriven.queenswood.form3-adapter.cop.handlers :as handlers]))

(def routes
  [["/cop"
    {:openapi {:tags ["CoP"]}}
    ["/outbound"
     {:post {:summary "Perform an outbound Confirmation of Payee check"
             :openapi {:operationId "OutboundCop"}
             :parameters {:body [:ref "CopOutboundRequest"]}
             :responses {200 {:body [:ref "CopOutboundResponse"]}}
             :handler handlers/outbound-cop}}]]])
