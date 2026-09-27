(ns com.repldriven.queenswood.modulr-simulator.deliveries
  "Delivers a notification to the URL a customer registered for its
  type, signed the way Modulr signs one: the HMAC `Signature` over the
  delivery's `Date` and `x-mod-nonce`, keyed by the registration's id
  and the secret set with it. A delivery the receiver does not accept is
  tried again a few times, as Modulr retries."
  (:require
    [com.repldriven.queenswood.modulr-simulator.ledger :as ledger]

    [com.repldriven.queenswood.modulr-webhook.interface :as modulr-webhook]

    [com.repldriven.mono.error.interface :as error :refer [let-nom>]]
    [com.repldriven.mono.http-client.interface :as http]
    [com.repldriven.mono.json.interface :as json]
    [com.repldriven.mono.log.interface :as log]
    [com.repldriven.mono.utility.interface :as utility]))

(def ^:private attempts 4)

(def ^:private retry-ms 250)

(defn- registration
  [state customer-id type]
  (or (when customer-id (ledger/notification state customer-id type))
      (some (fn [[_ by-type]] (get by-type type)) (:notifications @state))))

(defn- post
  [{:keys [id url secret hmacAlgorithm]} body]
  (let-nom> [headers (modulr-webhook/headers {:key-id id
                                              :secret secret
                                              :algorithm hmacAlgorithm}
                                             (modulr-webhook/nonce)
                                             (utility/now))]
    (http/request {:method :post
                   :url url
                   :headers (assoc headers "Content-Type" "application/json")
                   :body body})))

(defn- accepted?
  [res]
  (and (not (error/anomaly? res))
       (some-> (:status res)
               (< 300))))

(defn notify
  "POST `payload` as the `type` notification the account's customer
  registered for. Returns the last response, or nil when nobody
  registered for it."
  [state customer-id type payload]
  (if-let [r (registration state customer-id type)]
    (let [body (json/write-str payload)]
      (loop [n 1]
        (let [res (post r body)]
          (cond
           (accepted? res)
           res

           (< n attempts)
           (do (Thread/sleep (long (* n retry-ms))) (recur (inc n)))

           :else
           (do (log/error "Modulr simulator delivery failed"
                          {:type type :url (:url r) :res res})
               res)))))
    (log/warn "No notification registered" {:type type :customer customer-id})))
