(ns com.repldriven.queenswood.api.webhook.document
  "The document's top-level `webhooks` object: one OpenAPI 3.1 path
  item per public kind the bank publishes, each a POST of the
  notification to the tenant's registered address.

  The kinds come from the `webhook` component's catalogue, so a kind
  added there is published here by the same edit. The request body is
  a `$ref` to the notification component rather than a shape restated
  here — a resource gaining a field gains it in its notifications with
  no second schema to keep.

  `webhook-id`, `webhook-timestamp` and `webhook-signature` are the
  Standard Webhooks header names, so declaring them here restates the
  specification rather than this bank's own choice."
  (:require
    [com.repldriven.queenswood.webhook.interface :as webhook]

    [clojure.string :as str]))

(def ^:private signature-headers
  [{:name "webhook-id"
    :in "header"
    :required true
    :description "The delivery id the signature covers."
    :schema {:type "string"}}
   {:name "webhook-timestamp"
    :in "header"
    :required true
    :description "Seconds since the epoch, as the signature covers it."
    :schema {:type "string"}}
   {:name "webhook-signature"
    :in "header"
    :required true
    :description (str
                  "Space-separated `v1,<base64>` signatures over the id, the "
                  "timestamp and the body. A delivery sent inside a rotation "
                  "window carries one under each secret.")
    :schema {:type "string"}}])

(def ^:private request-body
  {:required true
   :content {"application/json"
             {:schema {:$ref "#/components/schemas/WebhookNotification"}
              :examples {"WebhookNotification"
                         {:$ref
                          "#/components/examples/WebhookNotification"}}}}})

(def ^:private responses
  {"2XX" {:description (str
                        "Any 2xx marks the delivery delivered. Anything else, "
                        "or no answer inside the call's bounds, is retried on "
                        "the schedule until the attempt limit.")}})

(defn- operation-id
  [kind]
  (str (str/join (map str/capitalize (str/split kind #"[.-]")))
       "Notification"))

(defn- path-item
  [{:keys [kind resource-type]}]
  {:post {:operationId (operation-id kind)
          :summary (str "The `" kind "` notification")
          :description
          (str "Sent to every enabled endpoint that has chosen `"
               kind
               "`. `data` carries the `"
               resource-type
               "` resource as it stood when the change committed.")
          :parameters signature-headers
          :requestBody request-body
          :responses responses}})

(def webhooks
  "The `webhooks` object, keyed by public kind. Injected through the
  `/openapi.json` route's `:openapi` data in `api.clj`, which reitit's
  assembler carries through untouched."
  (into {} (map (juxt :kind path-item)) webhook/published-kinds))
