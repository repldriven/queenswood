(ns com.repldriven.queenswood.clearbank-simulator.signed
  (:require
    [com.repldriven.queenswood.clearbank-webhook.interface :as
     clearbank-webhook]

    [com.repldriven.mono.error.interface :as error]

    [clojure.string :as str]))

(def ^:private signature-header
  (str/lower-case clearbank-webhook/signature-header))

(defn verified
  [handler]
  (fn [request]
    (let [{:keys [client-key headers raw-body]} request
          res (clearbank-webhook/verify (:public-key client-key)
                                        (get headers signature-header)
                                        (or raw-body (byte-array 0)))]
      (if (error/anomaly? res)
        {:status 401
         :body {:type (str (error/kind res))
                :title "UNAUTHORIZED"
                :status 401
                :detail (:message (error/payload res))}}
        (handler request)))))
