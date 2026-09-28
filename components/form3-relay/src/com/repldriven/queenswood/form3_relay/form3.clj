(ns com.repldriven.queenswood.form3-relay.form3
  (:require
    [com.repldriven.queenswood.form3-webhook.interface :as form3-webhook]

    [com.repldriven.mono.error.interface :as error :refer [let-nom>]]
    [com.repldriven.mono.http-client.interface :as http]
    [com.repldriven.mono.json.interface :as json]
    [com.repldriven.mono.utility.interface :as utility]

    [clojure.string :as str])
  (:import
    (java.math BigDecimal RoundingMode)
    (java.net URI URLEncoder)
    (java.nio.charset StandardCharsets)))

(def ^:private timeout-ms 10000)

(def content-type "application/vnd.api+json")

(defn ->major-units
  [minor-units]
  (.toPlainString (.setScale (.movePointLeft (BigDecimal/valueOf
                                              (long minor-units))
                                             2)
                             2
                             RoundingMode/UNNECESSARY)))

(defn- encode
  [s]
  (URLEncoder/encode (str s) StandardCharsets/UTF_8))

(defn- query-string
  [query]
  (when (seq query)
    (str/join "&"
              (map (fn [[k v]] (str (encode (name k)) "=" (encode v)))
                   (sort-by (comp name key) query)))))

(defn- host
  [^URI uri]
  (if (neg? (.getPort uri))
    (.getHost uri)
    (str (.getHost uri) ":" (.getPort uri))))

(defn request
  "Make one call to Form3, signed with the adapter's key."
  [config {:keys [method path query body]}]
  (let [{:keys [form3-url credentials]} config
        qs (query-string query)
        url (str form3-url path (when qs (str "?" qs)))
        uri (URI. url)
        body (when body (json/write-str body))]
    (let-nom> [signed (error/try-nom :payment/signing
                                     "The request could not be signed"
                                     (form3-webhook/headers
                                      credentials
                                      {:method method
                                       :path (.getRawPath uri)
                                       :query (.getRawQuery uri)
                                       :host (host uri)
                                       :body body
                                       :content-type content-type}
                                      (utility/now)))]
      (let [res (http/request (utility/assoc-some
                               {:method method
                                :url url
                                :timeout timeout-ms
                                :headers (assoc signed "Accept" content-type)}
                               :body
                               body))]
        (if (error/anomaly? res)
          (error/fail :payment/unavailable
                      {:message "The payment provider is unreachable"
                       :cause res})
          res)))))

(defn- refusal-message
  [res]
  (let [body (http/res->edn res)]
    (or (when (map? body) (:error_message body)) (str "HTTP " (:status res)))))

(defn classify
  "`[:ok body]`, `[:exists message]` for a 409, `[:refused message]` or
  `[:retry message]` for a call's result."
  [res]
  (let [status (:status res)]
    (cond
     (error/anomaly? res)
     [:retry (:message (error/payload res))]

     (not (int? status))
     [:retry "no HTTP status"]

     (or (<= 500 status) (= 408 status) (= 429 status))
     [:retry (str "HTTP " status)]

     (= 409 status)
     [:exists (refusal-message res)]

     (<= 400 status 499)
     [:refused (refusal-message res)]

     (<= 200 status 299)
     [:ok (http/res->edn res)]

     :else
     [:retry (str "HTTP " status)])))
