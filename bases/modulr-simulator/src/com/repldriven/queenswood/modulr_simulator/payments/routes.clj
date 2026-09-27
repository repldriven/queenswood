(ns com.repldriven.queenswood.modulr-simulator.payments.routes
  (:require
    [com.repldriven.queenswood.modulr-simulator.payments.handlers :as
     handlers]))

(def routes
  [["/payments"
    {:openapi {:tags ["Payments"]}
     :post {:summary "Create a payment or a transfer between accounts"
            :openapi {:operationId "CreatePayment"}
            :parameters {:body [:ref "PaymentRequest"]}
            :responses {201 {:body [:ref "Payment"]}
                        400 {:body [:ref "Problem"]}}
            :handler handlers/create}
     :get {:summary "Retrieve payments by id, external reference or scheme id"
           :openapi {:operationId "GetPayments"}
           :parameters {:query [:map
                                [:id {:optional true} string?]
                                [:externalReference {:optional true} string?]
                                [:schemeId {:optional true} string?]]}
           :responses {200 {:body [:ref "PaymentPage"]}}
           :handler handlers/search}}]
   ["/credit"
    {:openapi {:tags ["Payments"]}
     :post {:summary "Credit an account, as the sandbox's mock inbound does"
            :openapi {:operationId "Credit"}
            :parameters {:body [:ref "CreditRequest"]}
            :responses {200 {:description "Credited."}}
            :handler handlers/credit}}]])
