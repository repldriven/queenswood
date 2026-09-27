(ns com.repldriven.queenswood.form3-simulator.deliveries
  "Delivers a notification, the envelope Form3 posts with the resource as
  a GET returns it, to every subscription for its record and event type.
  A delivery carries no signature, since Form3 documents none; one the
  receiver does not accept is tried again a few times."
  (:require
    [com.repldriven.queenswood.form3-simulator.records :as records]

    [com.repldriven.mono.error.interface :as error]
    [com.repldriven.mono.http-client.interface :as http]
    [com.repldriven.mono.json.interface :as json]
    [com.repldriven.mono.log.interface :as log]))

(def ^:private attempts 4)

(def ^:private retry-ms 250)

(defn- accepted?
  [res]
  (and (not (error/anomaly? res))
       (some-> (:status res)
               (< 300))))

(defn- post
  [url body]
  (loop [n 1]
    (let [res (http/request {:method :post
                             :url url
                             :headers {"Content-Type" "application/json"}
                             :body body})]
      (cond
       (accepted? res)
       res

       (< n attempts)
       (do (Thread/sleep (long (* n retry-ms))) (recur (inc n)))

       :else
       (do (log/error "Form3 simulator delivery failed" {:url url :res res})
           res)))))

(defn notify
  "POST the `event-type` notification of `record-type` for resource `r`
  to each subscription for it."
  [state organisation-id record-type event-type r]
  (let [body (json/write-str {:id (records/new-id)
                              :organisation_id organisation-id
                              :event_type event-type
                              :record_type record-type
                              :version 0
                              :action_time (records/timestamp)
                              :data (records/public r)})
        subs (records/subscriptions state record-type event-type)]
    (when (empty? subs)
      (log/warn "No subscription for a Form3 notification"
                {:record-type record-type :event-type event-type}))
    (doseq [{:keys [attributes]} subs]
      (post (:callback_uri attributes) body))))
