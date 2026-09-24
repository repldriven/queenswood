(ns com.repldriven.queenswood.api.webhook.routes
  (:require
    [com.repldriven.queenswood.api.examples :as api.examples]
    [com.repldriven.queenswood.api.webhook.handlers :as handlers]
    [com.repldriven.queenswood.api.webhook.queries :as queries]

    [com.repldriven.queenswood.api.shared.headers :as shared.headers]
    [com.repldriven.queenswood.api.shared.idempotency :as shared.idempotency]
    [com.repldriven.queenswood.api.shared.parameters :as shared.parameters]

    [com.repldriven.queenswood.api-schema.interface :refer
     [ErrorExamples ErrorResponse]]
    [com.repldriven.queenswood.idempotency.interface :as bank-idempotency]
    [com.repldriven.queenswood.webhook.interface :refer
     [WebhookEndpointNotFound WebhookDeliveryNotFound
      WebhookEndpointInvalidAddress WebhookEndpointInvalidStatus]]

    [com.repldriven.mono.server.interface :as server]))

(def ^:private list-deliveries-query-schema
  [:map {:closed true}
   [:filter {:optional true} [:ref "WebhookDeliveryFilterQuery"]]
   [:page {:optional true} [:ref "PageQuery"]]])

(def routes
  [["/webhook-endpoints"
    {:openapi {:tags ["Webhook Endpoints"]}}
    [""
     {:get {:summary "List the bank's webhook endpoints"
            :openapi {:operationId "ListWebhookEndpoints"
                      :description
                      (str "Removed endpoints are included. The signing "
                           "secret is never returned.")
                      :security [{"bearerAuth" ["org:viewer"]}]
                      :parameters ^:replace
                                  [shared.parameters/ref-page
                                   shared.parameters/ref-bank-id-header]}
            :parameters {:query shared.parameters/page-query}
            :responses {200 {:description "The bank's webhook endpoints."
                             :body [:ref "WebhookEndpointList"]}}
            :handler queries/list-endpoints}
      :post
      {:summary "Register a webhook endpoint"
       :openapi {:operationId "RegisterWebhookEndpoint"
                 :description (str "The response carries the signing secret, "
                                   "which no read returns. An "
                                   "address that is not HTTPS, names the "
                                   "platform's own host, or resolves to no "
                                   "address or to a private, loopback or "
                                   "link-local range is refused with 422. An "
                                   "endpoint that chooses no kinds receives "
                                   "every kind.")
                 :security [{"bearerAuth" ["org:developer"]}]
                 :requestBody {:required true}
                 :parameters ^:replace
                             [shared.parameters/ref-bank-id-header
                              shared.parameters/ref-idempotency-key]}
       :interceptors [server/require-idempotency-key
                      bank-idempotency/cache-response]
       :parameters {:body [:ref "WebhookEndpointRequest"]}
       :responses (shared.idempotency/with-responses
                   {201 {:description (str "The registered endpoint and its "
                                           "signing secret.")
                         :body [:ref "WebhookEndpointRegistration"]
                         :openapi {:headers {"Location" (shared.headers/location
                                                         "webhook endpoint")}}}
                    403 (ErrorExamples [#'api.examples/PolicyDenied])
                    422 (ErrorResponse [#'WebhookEndpointInvalidAddress])
                    429 (ErrorResponse [#'api.examples/PolicyLimitExceeded])})
       :handler handlers/register}}]
    ["/{endpoint-id}"
     {:parameters {:path {:endpoint-id [:ref "WebhookEndpointId"]}}}
     [""
      {:get {:summary "Retrieve a webhook endpoint"
             :openapi {:operationId "RetrieveWebhookEndpoint"
                       :description
                       (str "A removed endpoint is still returned. The "
                            "signing secret is never returned.")
                       :security [{"bearerAuth" ["org:viewer"]}]
                       :parameters [shared.parameters/ref-bank-id-header]}
             :responses {200 {:description "The endpoint."
                              :body [:ref "WebhookEndpoint"]}
                         404 (ErrorResponse [#'WebhookEndpointNotFound])}
             :handler queries/get-endpoint}
       :put {:summary "Update a webhook endpoint"
             :openapi
             {:operationId "UpdateWebhookEndpoint"
              :description
              (str "Sets the endpoint's address, description and chosen "
                   "kinds. An address that fails the registration checks is"
                   " refused with 422. A removed endpoint is refused with "
                   "409.")
              :security [{"bearerAuth" ["org:developer"]}]
              :parameters [shared.parameters/ref-bank-id-header]
              :requestBody {:required true}}
             :parameters {:body [:ref "WebhookEndpointRequest"]}
             :responses {200 {:description "The endpoint as replaced."
                              :body [:ref "WebhookEndpoint"]}
                         403 (ErrorExamples [#'api.examples/PolicyDenied])
                         404 (ErrorResponse [#'WebhookEndpointNotFound])
                         409 (ErrorResponse [#'WebhookEndpointInvalidStatus])
                         422 (ErrorResponse [#'WebhookEndpointInvalidAddress])}
             :handler handlers/update-endpoint}
       :delete
       {:summary "Remove a webhook endpoint"
        :openapi {:operationId "RemoveWebhookEndpoint"
                  :description
                  (str "The endpoint moves to the removed status "
                       "rather than being deleted, so it and its "
                       "deliveries stay readable. Removal is final "
                       "and it receives no further notifications. "
                       "An endpoint already removed is refused " "with 409.")
                  :security [{"bearerAuth" ["org:developer"]}]
                  :parameters [shared.parameters/ref-bank-id-header]}
        :responses {204 {:description "The endpoint was removed. No body."}
                    403 (ErrorExamples [#'api.examples/PolicyDenied])
                    404 (ErrorResponse [#'WebhookEndpointNotFound])
                    409 (ErrorResponse [#'WebhookEndpointInvalidStatus])}
        :handler handlers/remove-endpoint}}]
     ["/enable"
      {:openapi {:security [{"bearerAuth" ["org:developer"]}]
                 :parameters [shared.parameters/ref-bank-id-header]}
       :post {:summary "Enable a webhook endpoint"
              :openapi
              {:operationId "EnableWebhookEndpoint"
               :description
               (str "Only a disabled or paused endpoint can be enabled; any "
                    "other is refused with 409. With `since`, every "
                    "notification from that instant that the endpoint has "
                    "chosen and never received is queued as a new delivery.")
               :requestBody {:required true}}
              :parameters {:body [:ref "WebhookEndpointEnableRequest"]}
              :responses {200 {:description "The enabled endpoint."
                               :body [:ref "WebhookEndpoint"]}
                          403 (ErrorExamples [#'api.examples/PolicyDenied])
                          404 (ErrorResponse [#'WebhookEndpointNotFound])
                          409 (ErrorResponse [#'WebhookEndpointInvalidStatus])}
              :handler handlers/enable}}]
     ["/disable"
      {:openapi {:security [{"bearerAuth" ["org:developer"]}]
                 :parameters [shared.parameters/ref-bank-id-header]}
       :post {:summary "Disable a webhook endpoint"
              :openapi
              {:operationId "DisableWebhookEndpoint"
               :description
               (str "The endpoint receives no new notifications until it is "
                    "enabled again. Only an enabled or paused endpoint can "
                    "be disabled; any other is refused with 409.")}
              :responses {200 {:description "The disabled endpoint."
                               :body [:ref "WebhookEndpoint"]}
                          403 (ErrorExamples [#'api.examples/PolicyDenied])
                          404 (ErrorResponse [#'WebhookEndpointNotFound])
                          409 (ErrorResponse [#'WebhookEndpointInvalidStatus])}
              :handler handlers/disable}}]
     ["/rotate-secret"
      {:openapi {:security [{"bearerAuth" ["org:developer"]}]}
       :post {:summary "Rotate a webhook endpoint's signing secret"
              :openapi {:operationId "RotateWebhookEndpointSecret"
                        :description
                        (str "The response carries the new secret. Until "
                             "`previous-secret-expires-at`, a day later, "
                             "deliveries are signed with both the new and "
                             "the previous secret. A removed endpoint is "
                             "refused with 409.")
                        :parameters ^:replace
                                    [shared.parameters/ref-endpoint-id
                                     shared.parameters/ref-bank-id-header
                                     shared.parameters/ref-idempotency-key]}
              :interceptors [server/require-idempotency-key
                             bank-idempotency/cache-response]
              :responses
              (shared.idempotency/with-responses
               {200 {:description (str "The endpoint, the new secret, and "
                                       "when the previous one stops being "
                                       "accepted.")
                     :body [:ref "WebhookEndpointSecretRotation"]}
                403 (ErrorExamples [#'api.examples/PolicyDenied])
                404 (ErrorResponse [#'WebhookEndpointNotFound])
                409 (ErrorResponse [#'WebhookEndpointInvalidStatus])})
              :handler handlers/rotate-secret}}]
     ["/test-notification"
      {:openapi {:security [{"bearerAuth" ["org:developer"]}]}
       :post {:summary "Send a test notification to a webhook endpoint"
              :openapi
              {:operationId "SendWebhookTestNotification"
               :description
               (str "Sends a notification of kind `webhook.test`, carrying "
                    "the endpoint as its data, whatever kinds the endpoint "
                    "has chosen, and returns the delivery it created. The "
                    "endpoint must be enabled; otherwise the request is "
                    "refused with 409.")
               :parameters ^:replace
                           [shared.parameters/ref-endpoint-id
                            shared.parameters/ref-bank-id-header
                            shared.parameters/ref-idempotency-key]}
              :interceptors [server/require-idempotency-key
                             bank-idempotency/cache-response]
              :responses
              (shared.idempotency/with-responses
               {201 {:description "The delivery the test notification created."
                     :body [:ref "WebhookDelivery"]
                     :openapi {:headers {"Location" (shared.headers/location
                                                     "delivery")}}}
                403 (ErrorExamples [#'api.examples/PolicyDenied])
                404 (ErrorResponse [#'WebhookEndpointNotFound])
                409 (ErrorResponse [#'WebhookEndpointInvalidStatus])})
              :handler handlers/test-notification}}]
     ["/resend"
      {:openapi {:security [{"bearerAuth" ["org:developer"]}]}
       :post {:summary "Resend a webhook endpoint's notifications"
              :openapi
              {:operationId "ResendWebhookNotifications"
               :description
               (str "Queues every notification the endpoint has chosen that "
                    "the bank created between `from` and `to` as a new "
                    "delivery, whether or not it was delivered before. "
                    "Without `to`, the window runs to now. The endpoint must"
                    " be enabled; otherwise the request is refused with 409.")
               :requestBody {:required true}
               :parameters ^:replace
                           [shared.parameters/ref-endpoint-id
                            shared.parameters/ref-bank-id-header
                            shared.parameters/ref-idempotency-key]}
              :interceptors [server/require-idempotency-key
                             bank-idempotency/cache-response]
              :parameters {:body [:ref "WebhookResendWindowRequest"]}
              :responses
              (shared.idempotency/with-responses
               {200 {:description (str "One new delivery per notification "
                                       "in the window.")
                     :body [:ref "WebhookDeliveryList"]}
                403 (ErrorExamples [#'api.examples/PolicyDenied])
                404 (ErrorResponse [#'WebhookEndpointNotFound])
                409 (ErrorResponse [#'WebhookEndpointInvalidStatus])})
              :handler handlers/resend-window}}]
     ["/deliveries"
      {:openapi {:security [{"bearerAuth" ["org:viewer"]}]}
       :get {:summary "List a webhook endpoint's deliveries"
             :openapi {:operationId "ListWebhookDeliveries"
                       :description
                       (str "`filter[kind]`, `filter[outcome]`, "
                            "`filter[from]` and `filter[to]` narrow the "
                            "list by notification kind, delivery status "
                            "and when the delivery was created. A removed "
                            "endpoint's deliveries are still listed.")
                       :parameters ^:replace
                                   [shared.parameters/ref-endpoint-id
                                    shared.parameters/ref-delivery-filter
                                    shared.parameters/ref-page
                                    shared.parameters/ref-bank-id-header]}
             :parameters {:query list-deliveries-query-schema}
             :responses {200 {:description "The endpoint's deliveries."
                              :body [:ref "WebhookDeliveryList"]}
                         404 (ErrorResponse [#'WebhookEndpointNotFound])}
             :handler queries/list-deliveries}}]
     ["/deliveries/{delivery-id}/resend"
      {:openapi {:security [{"bearerAuth" ["org:developer"]}]}
       :parameters {:path {:delivery-id [:ref "WebhookDeliveryId"]}}
       :post {:summary "Resend a webhook delivery's notification"
              :openapi
              {:operationId "ResendWebhookDelivery"
               :description
               (str "Queues the delivery's notification again as a new "
                    "delivery; the original delivery keeps its attempts. "
                    "Returns 404 for a delivery that belongs to another "
                    "endpoint. The endpoint must be enabled; otherwise the "
                    "request is refused with 409.")
               :parameters ^:replace
                           [shared.parameters/ref-endpoint-id
                            shared.parameters/ref-delivery-id
                            shared.parameters/ref-bank-id-header
                            shared.parameters/ref-idempotency-key]}
              :interceptors [server/require-idempotency-key
                             bank-idempotency/cache-response]
              :responses
              (shared.idempotency/with-responses
               {201 {:description "The new delivery of the same notification."
                     :body [:ref "WebhookDelivery"]
                     :openapi {:headers {"Location" (shared.headers/location
                                                     "delivery")}}}
                403 (ErrorExamples [#'api.examples/PolicyDenied])
                404 (ErrorResponse [#'WebhookEndpointNotFound
                                    #'WebhookDeliveryNotFound])
                409 (ErrorResponse [#'WebhookEndpointInvalidStatus])})
              :handler handlers/resend}}]]]])
