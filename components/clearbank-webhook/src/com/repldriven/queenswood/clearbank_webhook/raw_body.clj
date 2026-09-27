(ns com.repldriven.queenswood.clearbank-webhook.raw-body
  (:import
    (java.io ByteArrayInputStream InputStream)))

(def interceptor
  {:name ::raw-body
   :enter (fn [ctx]
            (let [{:keys [^InputStream body]} (:request ctx)]
              (if body
                (let [bytes (with-open [in body] (.readAllBytes in))]
                  (update ctx
                          :request assoc
                          :raw-body bytes
                          :body (ByteArrayInputStream. bytes)))
                ctx)))})
