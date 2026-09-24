(ns com.repldriven.queenswood.api.payment.queries
  (:require
    [com.repldriven.queenswood.api.cursor :as cursor]
    [com.repldriven.queenswood.api.errors :as errors]

    [com.repldriven.queenswood.payment-api.interface :as coercion]
    [com.repldriven.queenswood.payment-query.interface :as payments]

    [com.repldriven.mono.error.interface :as error]
    [com.repldriven.mono.utility.interface :as utility]))

(def ^:private not-found
  {:status 404
   :body (errors/error-response 404 "REJECTED"
                                ":payment/not-found" "Payment not found")})

(defn- payment-response
  [result]
  (cond
   (error/anomaly? result)
   (errors/anomaly->response result)

   (nil? result)
   not-found

   :else
   {:status 200 :body result}))

(defn- read-payment
  [find-payment request]
  (let [{:keys [auth parameters]} request
        {:keys [bank-id]} auth
        {:keys [path]} parameters
        {:keys [payment-id]} path]
    (payment-response (find-payment request bank-id payment-id))))

(defn get-internal-payment
  [request]
  (read-payment payments/find-internal-payment request))

(defn get-outbound-payment
  [request]
  (read-payment payments/find-outbound-payment request))

(defn get-inbound-payment
  [request]
  (read-payment payments/find-inbound-payment request))

(defn- inbound-path
  [status]
  (str "/v1/payments/inbound?status="
       (coercion/encode-inbound-payment-status status)))

(defn list-inbound-payments
  [request]
  (let [{:keys [auth parameters]} request
        {:keys [bank-id]} auth
        {:keys [query]} parameters
        {:keys [status page]} query
        {:keys [after before size]} page
        result (payments/list-inbound-payments request bank-id status)]
    (if (error/anomaly? result)
      (errors/anomaly->response result)
      (let [{windowed :page next-cursor :after prev-cursor :before}
            (cursor/paginate result
                             :payment-id
                             :desc
                             {:after (cursor/decode after)
                              :before (cursor/decode before)
                              :size size})
            links (when (seq windowed)
                    (cursor/build-links (inbound-path status)
                                        (cursor/clamp-size size)
                                        (when after prev-cursor)
                                        next-cursor))]
        {:status 200
         :body (utility/assoc-seq {:items windowed} :links links)}))))
