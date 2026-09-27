(ns com.repldriven.queenswood.form3-webhook.raw-body
  (:require
    [clojure.string :as str])
  (:import
    (java.io ByteArrayInputStream InputStream)))

(def ^:private json-api "application/vnd.api+json")

(defn- as-json
  [headers]
  (let [content-type (get headers "content-type")]
    (if (and content-type (str/starts-with? content-type json-api))
      (assoc headers
             "content-type"
             (str/replace-first content-type json-api "application/json"))
      headers)))

(def interceptor
  {:name ::raw-body
   :enter (fn [ctx]
            (let [{:keys [^InputStream body headers]} (:request ctx)
                  bytes (if body (with-open [in body] (.readAllBytes in)) nil)]
              (update ctx
                      :request
                      (fn [request]
                        (cond-> (assoc request
                                       :raw-headers headers
                                       :headers (as-json headers))

                                (:content-type request)
                                (assoc :content-type
                                       (get (as-json headers) "content-type"))

                                bytes
                                (assoc :raw-body bytes
                                       :body (ByteArrayInputStream.
                                              bytes)))))))})
