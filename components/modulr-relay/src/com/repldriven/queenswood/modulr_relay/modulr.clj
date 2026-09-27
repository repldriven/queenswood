(ns com.repldriven.queenswood.modulr-relay.modulr
  (:require
    [com.repldriven.queenswood.modulr-webhook.interface :as modulr-webhook]

    [com.repldriven.mono.error.interface :as error :refer [let-nom>]]
    [com.repldriven.mono.http-client.interface :as http]
    [com.repldriven.mono.json.interface :as json]
    [com.repldriven.mono.utility.interface :as utility]

    [clojure.string :as str]))

(def ^:private timeout-ms 10000)

(defn ->reference
  [id]
  (str/replace id "." "-"))

(defn reference->id
  [reference]
  (when reference (str/replace-first reference "-" ".")))

(defn ->major-units
  [minor-units]
  (.movePointLeft (BigDecimal/valueOf (long minor-units)) 2))

(defn request
  [config {:keys [method path query body raw-body nonce retry?]}]
  (let [{:keys [modulr-url credentials]} config]
    (let-nom> [signed (modulr-webhook/headers credentials
                                              (or nonce (modulr-webhook/nonce))
                                              (utility/now))]
      (let [res (http/request
                 (utility/assoc-some
                  {:method method
                   :url (str modulr-url path)
                   :timeout timeout-ms
                   :headers (cond-> (assoc signed
                                           "Content-Type" "application/json"
                                           "Accept" "application/json")
                                    retry?
                                    (assoc "x-mod-retry" "true"))}
                  :query-params query
                  :body (cond
                         raw-body
                         raw-body

                         body
                         (json/write-str body))))]
        (if (error/anomaly? res)
          (error/fail :payment/unavailable
                      {:message "The payment provider is unreachable"
                       :cause res})
          res)))))

(defn- refusal-message
  [res]
  (let [body (http/res->edn res)
        first-message (when (sequential? body) (:message (first body)))]
    (or first-message
        (when (map? body) (or (:message body) (:detail body)))
        (str "HTTP " (:status res)))))

(defn classify
  "`[:ok body]`, `[:refused message]` or `[:retry message]` for a call's
  result."
  [res]
  (let [status (:status res)]
    (cond
     (error/anomaly? res)
     [:retry (:message (error/payload res))]

     (not (int? status))
     [:retry "no HTTP status"]

     (or (<= 500 status) (= 408 status) (= 429 status))
     [:retry (str "HTTP " status)]

     (<= 400 status 499)
     [:refused (refusal-message res)]

     (<= 200 status 299)
     [:ok (http/res->edn res)]

     :else
     [:retry (str "HTTP " status)])))
