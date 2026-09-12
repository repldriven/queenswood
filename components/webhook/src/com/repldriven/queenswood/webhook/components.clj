(ns com.repldriven.queenswood.webhook.components
  (:require
    [com.repldriven.queenswood.webhook.coercion :as coercion]
    [com.repldriven.queenswood.webhook.examples :as examples]

    [com.repldriven.queenswood.api-schema.interface :as schema :refer
     [components-registry]]
    [com.repldriven.queenswood.cash-account-api.interface :as
     cash-account-api]))

;; ---------------------------------------------------------------------------
;; The endpoint and delivery resources

(def WebhookEndpointId
  (schema/id-schema "WebhookEndpointId" "whe" examples/WebhookEndpointId))

(def WebhookDeliveryId
  (schema/id-schema "WebhookDeliveryId" "whd" examples/WebhookDeliveryId))

(def WebhookNotificationId
  (schema/id-schema "WebhookNotificationId"
                    "whn"
                    examples/WebhookNotificationId))

(def WebhookEndpointStatus
  (coercion/endpoint-status-enum-schema {:json-schema/example "enabled"}))

(def WebhookDeliveryStatus
  (coercion/delivery-status-enum-schema {:json-schema/example "delivered"}))

(def WebhookAddress
  [:re
   {:title "WebhookAddress"
    :json-schema/example (:address examples/endpoint)
    :description
    "The HTTPS URL the bank calls. A non-HTTPS address, and one whose
    host resolves into a loopback, link-local, private, unique-local,
    carrier-grade-NAT or unspecified range, is refused — by the domain
    rule, as 422 `webhook-endpoint/invalid-address`, so the scheme is
    not pinned here: a pattern refusing it would answer 400 instead and
    the rule would never run."}
   #"^[a-zA-Z][a-zA-Z0-9+.-]*://[^\s]{1,2000}$"])

(def WebhookSigningSecret
  [:re
   {:title "WebhookSigningSecret"
    :json-schema/example examples/WebhookSigningSecret
    :description
    "The key the delivery signature is computed under. Returned by
    registration and by rotation, and by nothing else — the bank keeps
    no way to show it again."}
   #"^whsec_[A-Za-z0-9_-]{16,}$"])

(def WebhookNotificationKind
  [:re
   {:title "WebhookNotificationKind"
    :json-schema/example examples/WebhookNotificationKind
    :description
    "The public name of the change, as `<resource>.<change>`. An
    endpoint subscribes to a set of these."}
   #"^[a-z0-9]+(-[a-z0-9]+)*\.[a-z0-9]+(-[a-z0-9]+)*$"])

(def WebhookEndpoint
  [:map {:json-schema/example examples/endpoint}
   [:bank-id [:ref "BankId"]]
   [:endpoint-id [:ref "WebhookEndpointId"]]
   [:address [:ref "WebhookAddress"]]
   [:description {:optional true} [:ref "Name"]]
   [:kinds {:optional true}
    [:unique-vector-lax [:ref "WebhookNotificationKind"]]]
   [:status [:ref "WebhookEndpointStatus"]]
   [:last-success-at {:optional true} [:ref "Timestamp"]]
   [:created-at [:ref "Timestamp"]]
   [:updated-at {:optional true} [:ref "Timestamp"]]])

(def ^:private endpoint-keys
  (into [] (comp (filter vector?) (map first)) WebhookEndpoint))

(defn ->endpoint-body
  "Project a stored endpoint onto the keys `WebhookEndpoint` declares.
  The secret, the rotated-away secret and the two idempotency keys are
  not among them, so none of them can reach a body through this."
  [endpoint]
  (select-keys endpoint endpoint-keys))

(def WebhookEndpointRequest
  [:map {:closed true :json-schema/example examples/WebhookEndpointRequest}
   [:address [:ref "WebhookAddress"]]
   [:description {:optional true} [:ref "Name"]]
   [:kinds {:optional true}
    [:unique-vector [:ref "WebhookNotificationKind"]]]])

(def WebhookEndpointRegistration
  [:map {:closed true :json-schema/example examples/WebhookEndpointRegistration}
   [:endpoint [:ref "WebhookEndpoint"]]
   [:secret [:ref "WebhookSigningSecret"]]])

(def WebhookEndpointSecretRotation
  [:map
   {:closed true :json-schema/example examples/WebhookEndpointSecretRotation}
   [:endpoint [:ref "WebhookEndpoint"]]
   [:secret [:ref "WebhookSigningSecret"]]
   [:previous-secret-expires-at [:ref "Timestamp"]]])

(def WebhookEndpointEnableRequest
  [:map
   {:closed true :json-schema/example examples/WebhookEndpointEnableRequest}
   [:since {:optional true} [:ref "Timestamp"]]])

(def WebhookResendWindowRequest
  [:map {:closed true :json-schema/example examples/WebhookResendWindowRequest}
   [:from [:ref "Timestamp"]]
   [:to {:optional true} [:ref "Timestamp"]]])

(def WebhookListLinks
  [:map
   [:next {:optional true} string?]
   [:prev {:optional true} string?]])

(def WebhookEndpointList
  [:map {:json-schema/example examples/WebhookEndpointList}
   [:items [:vector [:ref "WebhookEndpoint"]]]
   [:links {:optional true} [:ref "WebhookListLinks"]]])

(def WebhookDelivery
  [:map {:json-schema/example examples/delivery}
   [:bank-id [:ref "BankId"]]
   [:delivery-id [:ref "WebhookDeliveryId"]]
   [:notification-id [:ref "WebhookNotificationId"]]
   [:endpoint-id [:ref "WebhookEndpointId"]]
   [:status [:ref "WebhookDeliveryStatus"]]
   [:kind [:ref "WebhookNotificationKind"]]
   [:attempts {:optional true} int?]
   [:next-attempt-at {:optional true} [:ref "Timestamp"]]
   [:last-response-status {:optional true} int?]
   [:last-error {:optional true} string?]
   [:created-at [:ref "Timestamp"]]
   [:updated-at {:optional true} [:ref "Timestamp"]]])

(def ^:private delivery-keys
  (into [] (comp (filter vector?) (map first)) WebhookDelivery))

(defn ->delivery-body
  "Project a stored delivery onto the keys `WebhookDelivery` declares.
  The runner's claim — its lease and the replica holding it — is not
  among them."
  [delivery]
  (select-keys delivery delivery-keys))

(def WebhookDeliveryFilterQuery
  "Nested `filter` deepObject query parameter on the delivery history.
  Wire form is `filter[kind]=cash-account.opened&filter[outcome]=failed`,
  nested into `{:kind …, :outcome …}` by the base's bracket interceptor
  before malli validation runs. `from` and `to` bound when the delivery
  was created."
  [:map {:closed true}
   [:kind {:optional true} [:ref "WebhookNotificationKind"]]
   [:outcome {:optional true} [:ref "WebhookDeliveryStatus"]]
   [:from {:optional true} [:ref "Timestamp"]]
   [:to {:optional true} [:ref "Timestamp"]]])

(def WebhookDeliveryList
  [:map {:json-schema/example examples/WebhookDeliveryList}
   [:items [:vector [:ref "WebhookDelivery"]]]
   [:links {:optional true} [:ref "WebhookListLinks"]]])

(def ^:private endpoint-registry
  (components-registry
   [#'WebhookAddress #'WebhookDelivery #'WebhookDeliveryFilterQuery
    #'WebhookDeliveryId #'WebhookDeliveryList #'WebhookDeliveryStatus
    #'WebhookEndpoint #'WebhookEndpointEnableRequest #'WebhookEndpointId
    #'WebhookEndpointList #'WebhookEndpointRegistration #'WebhookEndpointRequest
    #'WebhookEndpointSecretRotation #'WebhookEndpointStatus #'WebhookListLinks
    #'WebhookNotificationId #'WebhookNotificationKind
    #'WebhookResendWindowRequest #'WebhookSigningSecret]))

;; ---------------------------------------------------------------------------
;; The notification resource

(def resource-components
  "The resource types a notification may carry, each against the name
  its resource publishes under in `components/schemas`. The catalogue
  names one of these types per public kind, so a kind whose type is
  missing here has no `oneOf` member to project onto.

  `WebhookEndpoint` is here for the test notification, which carries
  the endpoint it was sent to and belongs to no catalogue entry."
  {"CashAccount" "CashAccount" "WebhookEndpoint" "WebhookEndpoint"})

(def resource-registries
  "The registries `resource-components` is held against, merged — the
  `<domain>-api` registry a resource publishes under, and this
  component's own for the endpoint."
  (merge cash-account-api/registry endpoint-registry))

(defn unknown-resource-types
  [resource->component]
  (into (sorted-set)
        (keep (fn [[resource-type component]]
                (when-not (contains? resource-registries component)
                  resource-type)))
        resource->component))

(defn- component-ref
  [component]
  (str "#/components/schemas/" component))

(defn notification-data
  "The notification's `data`: a `oneOf` over the components
  `resource->component` names, discriminated on the envelope's
  `resource-type`. The projection is the node's whole JSON schema, so
  each member is a `$ref` to a resource's own component and no
  resource shape is restated here."
  [resource->component]
  (let [resource-types (sort (keys resource->component))
        ref-of (fn [resource-type]
                 (component-ref (get resource->component resource-type)))]
    [:map
     {:json-schema
      {:oneOf (mapv (fn [resource-type] {:$ref (ref-of resource-type)})
                    resource-types)
       :discriminator
       {:propertyName "resource-type"
        :mapping (reduce (fn [m resource-type]
                           (assoc m resource-type (ref-of resource-type)))
                         (sorted-map)
                         resource-types)}}}]))

(def WebhookNotificationData (notification-data resource-components))

(def WebhookResourceType
  (into [:enum
         {:title "WebhookResourceType"
          :json-schema/example (:resource-type examples/notification)
          :description "The kind of resource `data` carries."}]
        (sort (keys resource-components))))

(def WebhookNotification
  [:map {:json-schema/example examples/notification}
   [:notification-id [:ref "WebhookNotificationId"]]
   [:kind [:ref "WebhookNotificationKind"]]
   [:change-kind {:optional true}
    [:string {:json-schema/example (:change-kind examples/notification)}]]
   [:occurred-at [:ref "Timestamp"]]
   [:bank-id [:ref "BankId"]]
   [:resource-type [:ref "WebhookResourceType"]]
   [:resource-id
    [:re
     {:json-schema/example (:resource-id examples/notification)
      :description "The changed resource's id, in its own prefixed form."}
     #"^[a-z]+\.[0-9a-hjkmnp-tv-z]{26}$"]]
   [:status-before {:optional true}
    [:string {:json-schema/example (:status-before examples/notification)}]]
   [:status-after {:optional true}
    [:string {:json-schema/example (:status-after examples/notification)}]]
   [:idempotency-key {:optional true} [:ref "IdempotencyKey"]]
   [:correlation-id
    [:re
     {:json-schema/example (:correlation-id examples/notification)
      :description "The correlating id the source event carried."}
     #"^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$"]]
   [:data [:ref "WebhookNotificationData"]]])

(def registry
  (merge endpoint-registry
         (components-registry [#'WebhookNotification #'WebhookNotificationData
                               #'WebhookResourceType])))
