(ns com.repldriven.queenswood.demo-digital-bank-api.account.routes
  (:require
    [com.repldriven.queenswood.demo-digital-bank-api.account.handlers :as
     handlers]
    [com.repldriven.queenswood.demo-digital-bank-api.errors :as errors]
    [com.repldriven.queenswood.demo-digital-bank-api.shared.parameters :as
     parameters]))

(def routes
  [["/accounts"
    {:openapi {:tags ["Accounts"] :security [{"sessionAuth" []}]}
     :post {:summary "Open an account against one of the bank's products"
            :openapi {:operationId "OpenAccount"
                      :parameters [parameters/idempotency-key]}
            :parameters {:body [:ref "OpenAccountRequest"]}
            :responses
            (assoc errors/responses 201 {:body [:ref "OpenedAccount"]})
            :handler handlers/open}}]])
