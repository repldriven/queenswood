(ns com.repldriven.queenswood.form3-simulator.subscriptions.routes
  (:require
    [com.repldriven.queenswood.form3-simulator.subscriptions.handlers
     :as handlers]))

(def routes
  [["/notification/subscriptions"
    {:openapi {:tags ["Subscriptions"]}
     :post {:summary "Subscribe a callback to a record and event type"
            :openapi {:operationId "CreateSubscription"}
            :parameters {:body [:ref "ResourceRequest"]}
            :responses {201 {:body [:ref "ResourceResponse"]}}
            :handler handlers/register}
     :get {:summary "List subscriptions"
           :openapi {:operationId "ListSubscriptions"}
           :responses {200 {:body [:ref "ResourceListResponse"]}}
           :handler handlers/list-registered}}]
   ["/notification/subscriptions/{id}"
    {:openapi {:tags ["Subscriptions"]}
     :delete {:summary "Delete a subscription"
              :openapi {:operationId "DeleteSubscription"}
              :parameters {:path [:map [:id string?]]}
              :responses {204 {:description "Deleted."}}
              :handler handlers/delete}}]])
