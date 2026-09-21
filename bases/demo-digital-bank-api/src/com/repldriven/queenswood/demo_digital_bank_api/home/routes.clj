(ns com.repldriven.queenswood.demo-digital-bank-api.home.routes
  (:require
    [com.repldriven.queenswood.demo-digital-bank-api.errors :as errors]
    [com.repldriven.queenswood.demo-digital-bank-api.home.handlers :as
     handlers]))

(def routes
  [["/me"
    {:openapi {:tags ["Home"] :security [{"sessionAuth" []}]}
     :get {:summary "Everything the home screen shows"
           :openapi {:operationId "RetrieveMe"}
           :responses (assoc errors/responses 200 {:body [:ref "Me"]})
           :handler handlers/me}}]
   ["/events"
    {:openapi {:tags ["Home"] :security [{"sessionAuth" []}]}
     :get {:summary "The customer's notifications, as they are told"
           :description
           (str "A server-sent event stream, held open. What the customer "
                "has not been shown arrives first, then each notification "
                "as the platform tells the bank; each is an event named "
                "`notification` whose data is a Notification.")
           ;; The stream's shape is documented here rather than declared
           ;; under `:responses`: a declared body would be coerced, and
           ;; the body is held open rather than answered.
           :openapi {:operationId "StreamEvents"
                     :responses
                     {200 {:description "The stream."
                           :content {"text/event-stream"
                                     {:schema
                                      {:$ref
                                       "#/components/schemas/Notification"}}}}}}
           :responses (assoc errors/responses 200 {:description "The stream."})
           :handler handlers/events}}]])
