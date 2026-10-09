(ns com.repldriven.queenswood.clearbank-relay.outbound.shared
  (:require
    [com.repldriven.queenswood.clearbank-webhook.interface :as
     clearbank-webhook]

    [com.repldriven.mono.error.interface :as error :refer [let-nom>]]
    [com.repldriven.mono.http-client.interface :as http]
    [com.repldriven.mono.transit.interface :as transit]))

(defn post-signed
  "POST a signed request to the provider. An unreachable provider is
  `:payment/unavailable` — the kind names the domain, not the vendor,
  because a second scheme provider consuming this channel must not
  change what the failure is called (ADR-0020)."
  [url signing-key request-body]
  (error/try-nom
   :payment/unavailable
   "Failed to POST outbound payment to the scheme adapter"
   (let [res (let-nom> [signature (clearbank-webhook/sign
                                   (:private-key signing-key)
                                   request-body)]
               (http/request {:method :post
                              :url url
                              :headers {"Content-Type" "application/json"
                                        clearbank-webhook/signature-header
                                        signature}
                              :body request-body}))]
     (if (error/anomaly? res)
       (error/fail :payment/unavailable
                   {:message "Scheme adapter unreachable"
                    :cause res})
       res))))

(defn context
  [intent]
  (or (some-> (not-empty (:context intent))
              transit/read-str)
      {}))

(defn post
  [config path request]
  (let [{:keys [clearbank-url signing-key post-fn]} config]
    ((or post-fn post-signed) (str clearbank-url path) signing-key request)))

(defn undelivered
  [failure reason]
  (if (= :undelivered failure) (str "Undelivered: " reason) reason))

(defn account-event
  [intent event-name data]
  {:event-name event-name
   :dedup-key (str (:idempotency-key intent) ":" event-name)
   :data data})
