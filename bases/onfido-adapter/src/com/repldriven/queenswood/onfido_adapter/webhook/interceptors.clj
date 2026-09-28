(ns com.repldriven.queenswood.onfido-adapter.webhook.interceptors
  (:require
    [com.repldriven.queenswood.onfido-webhook.interface :as onfido-webhook])
  (:import
    (java.io ByteArrayInputStream InputStream)))

(def raw-body
  "Keeps a delivery's body as it arrived, as `:raw-body`, before
  anything decodes it: Onfido's signature covers those bytes and no
  re-encoding of them. First in the router's chain, so it runs ahead of
  the request decoder, and only the webhook's path pays for the copy."
  {:name ::raw-body
   :enter (fn [ctx]
            (let [{:keys [uri ^InputStream body]} (:request ctx)]
              (if (and (= onfido-webhook/path uri) body)
                (let [bytes (with-open [in body] (.readAllBytes in))]
                  (update ctx
                          :request assoc
                          :raw-body bytes
                          :body (ByteArrayInputStream. bytes)))
                ctx)))})
