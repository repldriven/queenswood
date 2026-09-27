(ns com.repldriven.queenswood.modulr-simulator.name-check.routes
  (:require
    [com.repldriven.queenswood.modulr-simulator.name-check.handlers
     :as handlers]))

(def routes
  [["/account-name-check"
    {:openapi {:tags ["Confirmation of Payee"]}
     :post {:summary "Check a payee's name against their account"
            :openapi {:operationId "AccountNameCheck"}
            :parameters {:body [:ref "NameCheckRequest"]}
            :responses {201 {:body [:ref "NameCheckResponse"]}
                        400 {:body [:ref "Problem"]}}
            :handler handlers/check}}]])
