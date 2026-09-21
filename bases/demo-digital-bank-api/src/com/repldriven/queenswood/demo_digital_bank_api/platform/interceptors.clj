(ns com.repldriven.queenswood.demo-digital-bank-api.platform.interceptors
  (:import
    (java.io ByteArrayInputStream InputStream)))

(def receiver-path "/webhooks")

(def raw-body
  "Keeps a delivery's body as it arrived, as `:raw-body`, before
  anything decodes it: the platform's signature covers those bytes and
  no re-encoding of them. First in the router's chain, so it runs ahead
  of the request decoder, and only the receiver's path pays for the
  copy."
  {:name ::raw-body
   :enter (fn [ctx]
            (let [{:keys [uri ^InputStream body]} (:request ctx)]
              (if (and (= receiver-path uri) body)
                (let [bytes (with-open [in body] (.readAllBytes in))]
                  (update ctx
                          :request assoc
                          :raw-body bytes
                          :body (ByteArrayInputStream. bytes)))
                ctx)))})
