(ns com.repldriven.queenswood.demo-digital-bank-api.payment.routes
  (:require
    [com.repldriven.queenswood.demo-digital-bank-api.errors :as errors]
    [com.repldriven.queenswood.demo-digital-bank-api.payment.handlers :as
     handlers]
    [com.repldriven.queenswood.demo-digital-bank-api.shared.parameters :as
     parameters]))

(def routes
  [["/payee-checks"
    {:openapi {:tags ["Payments"] :security [{"sessionAuth" []}]}
     :post {:summary "Check a payee's name with their bank"
            :openapi {:operationId "CheckPayee"}
            :parameters {:body [:ref "PayeeCheckRequest"]}
            :responses (assoc errors/responses 200 {:body [:ref "PayeeCheck"]})
            :handler handlers/check-payee}}]
   ["/payments"
    {:openapi {:tags ["Payments"] :security [{"sessionAuth" []}]}
     :post {:summary "Pay a payee from one of the customer's accounts"
            :openapi {:operationId "SubmitPayment"
                      :parameters [parameters/idempotency-key]}
            :parameters {:body [:ref "PaymentRequest"]}
            :responses (assoc errors/responses 201 {:body [:ref "Payment"]})
            :handler handlers/submit}}]
   ["/transfers"
    {:openapi {:tags ["Payments"] :security [{"sessionAuth" []}]}
     :post {:summary "Move money between two of the customer's accounts"
            :openapi {:operationId "Transfer"
                      :parameters [parameters/idempotency-key]}
            :parameters {:body [:ref "TransferRequest"]}
            :responses (assoc errors/responses 201 {:body [:ref "Transfer"]})
            :handler handlers/transfer}}]])
