(ns com.repldriven.queenswood.onfido-simulator.webhook
  "Delivers an event to every webhook registered for it, signed as
  Onfido signs: `X-SHA2-Signature` carries the hex HMAC-SHA256 of the
  body keyed by the webhook's token. A webhook registered with no
  `events` receives every event."
  (:require
    [com.repldriven.queenswood.onfido-webhook.interface :as onfido-webhook]

    [com.repldriven.mono.error.interface :as error]
    [com.repldriven.mono.http-client.interface :as http]
    [com.repldriven.mono.json.interface :as json]
    [com.repldriven.mono.log.interface :as log])
  (:import
    (java.nio.charset StandardCharsets)))

(defn- post
  [{:keys [url token]} body]
  (let [res (http/request {:method :post
                           :url url
                           :headers {"Content-Type" "application/json"
                                     "X-SHA2-Signature"
                                     (onfido-webhook/sign token body)}
                           :body body})]
    (when (or (error/anomaly? res)
              (and (:status res) (>= (:status res) 400)))
      (log/error "Onfido webhook delivery failed to" url ":" res))
    res))

(defn send-event
  "POSTs `{payload {resource_type action object}}` to every webhook on
  `state` registered for `action`."
  [state resource-type action object]
  (let [body (.getBytes ^String
                        (json/write-str {:payload
                                         {:resource_type resource-type
                                          :action action
                                          :object object}})
                        StandardCharsets/UTF_8)
        hooks (filter (fn [{:keys [events]}]
                        (or (empty? events) (some #{action} events)))
                      (:webhooks @state))]
    (if (empty? hooks)
      (log/warn "No Onfido webhook registered for" action)
      (doseq [hook hooks] (post hook body)))))
