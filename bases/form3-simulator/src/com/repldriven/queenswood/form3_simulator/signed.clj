(ns com.repldriven.queenswood.form3-simulator.signed
  "Checks every API call is signed with the adapter's key, as Form3's
  HTTP Signatures require, the body's digest included on a write."
  (:require
    [com.repldriven.queenswood.form3-webhook.interface :as form3-webhook]

    [com.repldriven.mono.error.interface :as error]
    [com.repldriven.mono.utility.interface :as utility]))

(defn api-error
  "Form3's error body, `{error_code error_message}`."
  [status message]
  {:status status
   :body {:error_code (str (utility/uuidv7)) :error_message message}})

(defn verified
  [handler]
  (fn [request]
    (let [{:keys [credentials request-method uri query-string raw-headers
                  raw-body]}
          request
          {:keys [key-id public-key]} credentials
          res (form3-webhook/verify {key-id public-key}
                                    {:method request-method
                                     :path uri
                                     :query query-string
                                     :headers raw-headers
                                     :body raw-body}
                                    (utility/now))]
      (if (error/anomaly? res)
        (api-error 401 (:message (error/payload res)))
        (handler request)))))
