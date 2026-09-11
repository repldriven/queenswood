(ns com.repldriven.queenswood.webhook.components
  (:require
    [com.repldriven.queenswood.webhook.examples :as examples]

    [com.repldriven.queenswood.api-schema.interface :refer
     [components-registry]]
    [com.repldriven.queenswood.cash-account-api.interface :as
     cash-account-api]))

(def resource-components
  "The resource types a notification may carry, each against the name
  its resource publishes under in `components/schemas`. The catalogue
  names one of these types per public kind, so a kind whose type is
  missing here has no `oneOf` member to project onto."
  {"CashAccount" "CashAccount"})

(def resource-registries
  "The `<domain>-api` registries `resource-components` is held
  against, merged — `cash-account-api` alone in this slice."
  cash-account-api/registry)

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

(def WebhookNotificationId
  [:re
   {:title "WebhookNotificationId"
    :json-schema/example examples/WebhookNotificationId
    :description
    "Time-ordered notification identifier (uuidv7), the same across
    every delivery and re-send of the notification."}
   #"^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$"])

(def WebhookNotificationKind
  [:re
   {:title "WebhookNotificationKind"
    :json-schema/example examples/WebhookNotificationKind
    :description
    "The public name of the change, as `<resource>.<change>`. An
    endpoint subscribes to a set of these."}
   #"^[a-z0-9]+(-[a-z0-9]+)*\.[a-z0-9]+(-[a-z0-9]+)*$"])

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
  (components-registry [#'WebhookNotification #'WebhookNotificationData
                        #'WebhookNotificationId #'WebhookNotificationKind
                        #'WebhookResourceType]))
