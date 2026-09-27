(ns com.repldriven.queenswood.modulr-simulator.signed
  "Checks every API call is signed with the adapter's credentials, and
  answers a retry — the same nonce, marked `x-mod-retry` — with the
  response the first attempt got, without doing it again. A nonce used
  before and not marked a retry is refused."
  (:require
    [com.repldriven.queenswood.modulr-simulator.ledger :as ledger]

    [com.repldriven.queenswood.modulr-webhook.interface :as modulr-webhook]

    [com.repldriven.mono.error.interface :as error]
    [com.repldriven.mono.utility.interface :as utility]))

(defn- unauthorized
  [anomaly]
  {:status 401
   :body [{:code "PERMISSION"
           :errorCode (str (error/kind anomaly))
           :message (:message (error/payload anomaly))}]})

(def ^:private duplicate
  {:status 400
   :body [{:code "DUPLICATE" :message "The nonce has been used before"}]})

(defn verified
  [handler]
  (fn [request]
    (let [{:keys [credentials state headers]} request
          res (modulr-webhook/verify credentials headers (utility/now))]
      (if (error/anomaly? res)
        (unauthorized res)
        (let [{:keys [nonce]} res
              seen (ledger/seen-nonce state nonce)]
          (cond
           (and seen (= "true" (get headers "x-mod-retry")))
           seen

           seen
           duplicate

           :else
           (let [response (handler request)]
             (ledger/remember-nonce state nonce response)
             response)))))))

(defn refusal
  ([code message] (refusal 400 code message))
  ([status code message]
   {:status status :body [{:code code :message message}]}))
