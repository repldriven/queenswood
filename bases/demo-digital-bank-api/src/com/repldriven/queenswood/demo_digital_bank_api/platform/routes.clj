(ns com.repldriven.queenswood.demo-digital-bank-api.platform.routes
  (:require
    [com.repldriven.queenswood.demo-digital-bank-api.errors :as errors]
    [com.repldriven.queenswood.demo-digital-bank-api.platform.handlers :as
     handlers]
    [com.repldriven.queenswood.demo-digital-bank-api.platform.interceptors :as
     interceptors]))

(def routes
  [[interceptors/receiver-path
    {:openapi {:tags ["Platform"]}
     :post {:summary "Receive a signed delivery from the platform"
            :description
            (str "The platform's webhook endpoint. A delivery carries the "
                 "Standard Webhooks headers and a notification as the "
                 "platform's API document describes it; one that verifies "
                 "is answered at once and acted on afterwards.")
            :openapi {:operationId "ReceiveDelivery"
                      :requestBody {:required true
                                    :content {"application/json" {}}}}
            :responses (assoc errors/responses 202 {:body [:ref "Received"]})
            :handler handlers/receive}}]])
