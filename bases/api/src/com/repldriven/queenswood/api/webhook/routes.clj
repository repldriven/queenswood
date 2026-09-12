(ns com.repldriven.queenswood.api.webhook.routes
  (:require
    [com.repldriven.queenswood.api.webhook.handlers :as handlers]
    [com.repldriven.queenswood.api.webhook.queries :as queries]

    [com.repldriven.queenswood.api.shared.idempotency :as shared.idempotency]
    [com.repldriven.queenswood.api.shared.parameters :as shared.parameters]

    [com.repldriven.queenswood.api-schema.interface :refer [ErrorResponse]]
    [com.repldriven.queenswood.idempotency.interface :as bank-idempotency]
    [com.repldriven.queenswood.webhook.interface :refer
     [WebhookEndpointNotFound WebhookDeliveryNotFound
      WebhookEndpointInvalidAddress WebhookEndpointInvalidStatus]]

    [com.repldriven.mono.server.interface :as server]))

(def ^:private list-endpoints-query-schema
  [:map {:closed true} [:page {:optional true} [:ref "PageQuery"]]])

(def ^:private list-deliveries-query-schema
  [:map {:closed true}
   [:filter {:optional true} [:ref "WebhookDeliveryFilterQuery"]]
   [:page {:optional true} [:ref "PageQuery"]]])

(def ^:private endpoint-location-header
  {:schema {:type "string"} :description "URI of the registered endpoint"})

(def ^:private delivery-location-header
  {:schema {:type "string"} :description "URI of the created delivery"})

(def routes
  [["/webhook-endpoints"
    {:openapi {:tags ["Webhooks"]}}
    [""
     {:get {:summary "List the bank's webhook endpoints"
            :openapi {:operationId "ListWebhookEndpoints"
                      :security [{"bearerAuth" ["org:viewer"]}]
                      :parameters ^:replace [shared.parameters/ref-page]}
            :parameters {:query list-endpoints-query-schema}
            :responses {200 {:description "The bank's webhook endpoints."
                             :body [:ref "WebhookEndpointList"]}}
            :handler queries/list-endpoints}
      :post {:summary (str "Register a webhook endpoint (the only "
                           "response but rotation that carries the "
                           "signing secret)")
             :openapi {:operationId "RegisterWebhookEndpoint"
                       :security [{"bearerAuth" ["org:developer"]}]
                       :requestBody {:required true}
                       :parameters ^:replace
                                   [shared.parameters/ref-idempotency-key]}
             :interceptors [server/require-idempotency-key
                            bank-idempotency/cache-response]
             :parameters {:body [:ref "WebhookEndpointRequest"]}
             :responses
             (shared.idempotency/with-responses
              {201 {:description (str "The registered endpoint and its "
                                      "signing secret.")
                    :body [:ref "WebhookEndpointRegistration"]
                    :openapi {:headers {"Location" endpoint-location-header}}}
               422 (ErrorResponse [#'WebhookEndpointInvalidAddress])})
             :handler handlers/register}}]
    ["/{endpoint-id}"
     {:parameters {:path {:endpoint-id [:ref "WebhookEndpointId"]}}}
     [""
      {:get {:summary "Retrieve a webhook endpoint"
             :openapi {:operationId "RetrieveWebhookEndpoint"
                       :security [{"bearerAuth" ["org:viewer"]}]}
             :responses {200 {:description "The endpoint."
                              :body [:ref "WebhookEndpoint"]}
                         404 (ErrorResponse [#'WebhookEndpointNotFound])}
             :handler queries/get-endpoint}
       :put {:summary (str "Replace the endpoint's address, description "
                           "and chosen kinds")
             :openapi {:operationId "UpdateWebhookEndpoint"
                       :security [{"bearerAuth" ["org:developer"]}]
                       :requestBody {:required true}}
             :parameters {:body [:ref "WebhookEndpointRequest"]}
             :responses {200 {:description "The endpoint as replaced."
                              :body [:ref "WebhookEndpoint"]}
                         404 (ErrorResponse [#'WebhookEndpointNotFound])
                         409 (ErrorResponse [#'WebhookEndpointInvalidStatus])
                         422 (ErrorResponse [#'WebhookEndpointInvalidAddress])}
             :handler handlers/update-endpoint}
       :delete {:summary (str "Remove the endpoint (a terminal status "
                              "rather than a deletion, so its "
                              "deliveries stay readable)")
                :openapi {:operationId "RemoveWebhookEndpoint"
                          :security [{"bearerAuth" ["org:developer"]}]}
                :responses
                {204 {:description "The endpoint was removed. No body."}
                 404 (ErrorResponse [#'WebhookEndpointNotFound])
                 409 (ErrorResponse [#'WebhookEndpointInvalidStatus])}
                :handler handlers/remove-endpoint}}]
     ["/enable"
      {:openapi {:security [{"bearerAuth" ["org:developer"]}]}
       :post {:summary (str "Enable a disabled or paused endpoint, "
                            "optionally asking for the gap since an "
                            "instant")
              :openapi {:operationId "EnableWebhookEndpoint"
                        :requestBody {:required true}}
              :parameters {:body [:ref "WebhookEndpointEnableRequest"]}
              :responses {200 {:description "The enabled endpoint."
                               :body [:ref "WebhookEndpoint"]}
                          404 (ErrorResponse [#'WebhookEndpointNotFound])
                          409 (ErrorResponse [#'WebhookEndpointInvalidStatus])}
              :handler handlers/enable}}]
     ["/disable"
      {:openapi {:security [{"bearerAuth" ["org:developer"]}]}
       :post {:summary "Disable an enabled or paused endpoint"
              :openapi {:operationId "DisableWebhookEndpoint"}
              :responses {200 {:description "The disabled endpoint."
                               :body [:ref "WebhookEndpoint"]}
                          404 (ErrorResponse [#'WebhookEndpointNotFound])
                          409 (ErrorResponse [#'WebhookEndpointInvalidStatus])}
              :handler handlers/disable}}]
     ["/rotate-secret"
      {:openapi {:security [{"bearerAuth" ["org:developer"]}]}
       :post {:summary (str "Mint a new signing secret, keeping the "
                            "current one accepted until it expires")
              :openapi {:operationId "RotateWebhookEndpointSecret"
                        :parameters ^:replace
                                    [shared.parameters/ref-endpoint-id
                                     shared.parameters/ref-idempotency-key]}
              :interceptors [server/require-idempotency-key
                             bank-idempotency/cache-response]
              :responses
              (shared.idempotency/with-responses
               {200 {:description (str "The endpoint, the new secret, and "
                                       "when the previous one stops being "
                                       "accepted.")
                     :body [:ref "WebhookEndpointSecretRotation"]}
                404 (ErrorResponse [#'WebhookEndpointNotFound])
                409 (ErrorResponse [#'WebhookEndpointInvalidStatus])})
              :handler handlers/rotate-secret}}]
     ["/test-notification"
      {:openapi {:security [{"bearerAuth" ["org:developer"]}]}
       :post {:summary (str "Send a test notification, answering with "
                            "the delivery it created")
              :openapi {:operationId "SendWebhookTestNotification"
                        :parameters ^:replace
                                    [shared.parameters/ref-endpoint-id
                                     shared.parameters/ref-idempotency-key]}
              :interceptors [server/require-idempotency-key
                             bank-idempotency/cache-response]
              :responses
              (shared.idempotency/with-responses
               {201 {:description "The delivery the test notification created."
                     :body [:ref "WebhookDelivery"]
                     :openapi {:headers {"Location" delivery-location-header}}}
                404 (ErrorResponse [#'WebhookEndpointNotFound])
                409 (ErrorResponse [#'WebhookEndpointInvalidStatus])})
              :handler handlers/test-notification}}]
     ["/resend"
      {:openapi {:security [{"bearerAuth" ["org:developer"]}]}
       :post {:summary (str "Send every notification in a window again, "
                            "one new delivery each")
              :openapi {:operationId "ResendWebhookNotifications"
                        :requestBody {:required true}
                        :parameters ^:replace
                                    [shared.parameters/ref-endpoint-id
                                     shared.parameters/ref-idempotency-key]}
              :interceptors [server/require-idempotency-key
                             bank-idempotency/cache-response]
              :parameters {:body [:ref "WebhookResendWindowRequest"]}
              :responses
              (shared.idempotency/with-responses
               {200 {:description (str "One new delivery per notification "
                                       "in the window.")
                     :body [:ref "WebhookDeliveryList"]}
                404 (ErrorResponse [#'WebhookEndpointNotFound])
                409 (ErrorResponse [#'WebhookEndpointInvalidStatus])})
              :handler handlers/resend-window}}]
     ["/deliveries"
      {:openapi {:security [{"bearerAuth" ["org:viewer"]}]}
       :get {:summary (str "List the endpoint's deliveries, filtered by "
                           "kind, outcome and time")
             :openapi {:operationId "ListWebhookDeliveries"
                       :parameters ^:replace
                                   [shared.parameters/ref-endpoint-id
                                    shared.parameters/ref-delivery-filter
                                    shared.parameters/ref-page]}
             :parameters {:query list-deliveries-query-schema}
             :responses {200 {:description "The endpoint's deliveries."
                              :body [:ref "WebhookDeliveryList"]}
                         404 (ErrorResponse [#'WebhookEndpointNotFound])}
             :handler queries/list-deliveries}}]
     ["/deliveries/{delivery-id}/resend"
      {:openapi {:security [{"bearerAuth" ["org:developer"]}]}
       :parameters {:path {:delivery-id [:ref "WebhookDeliveryId"]}}
       :post {:summary "Send one delivery's notification again"
              :openapi {:operationId "ResendWebhookDelivery"
                        :parameters ^:replace
                                    [shared.parameters/ref-endpoint-id
                                     shared.parameters/ref-delivery-id
                                     shared.parameters/ref-idempotency-key]}
              :interceptors [server/require-idempotency-key
                             bank-idempotency/cache-response]
              :responses
              (shared.idempotency/with-responses
               {201 {:description "The new delivery of the same notification."
                     :body [:ref "WebhookDelivery"]
                     :openapi {:headers {"Location" delivery-location-header}}}
                404 (ErrorResponse [#'WebhookEndpointNotFound
                                    #'WebhookDeliveryNotFound])
                409 (ErrorResponse [#'WebhookEndpointInvalidStatus])})
              :handler handlers/resend}}]]]])
