(ns com.repldriven.queenswood.modulr-simulator.notifications.routes
  (:require
    [com.repldriven.queenswood.modulr-simulator.notifications.handlers
     :as handlers]))

(def routes
  [["/customers/{customerId}/integration-notifications"
    {:openapi {:tags ["Notifications"]}
     :post {:summary "Subscribe a URL to a notification type"
            :openapi {:operationId "CreateNotification"}
            :parameters {:path [:map [:customerId string?]]
                         :body [:ref "NotificationRequest"]}
            :responses {201 {:body [:ref "Notification"]}}
            :handler handlers/register}
     :get {:summary "List a customer's notification subscriptions"
           :openapi {:operationId "ListNotifications"}
           :parameters {:path [:map [:customerId string?]]}
           :responses {200 {:body [:map
                                   [:content
                                    [:vector [:ref "Notification"]]]]}}
           :handler handlers/list-registered}}]])
