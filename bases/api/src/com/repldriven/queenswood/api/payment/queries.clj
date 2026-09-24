(ns com.repldriven.queenswood.api.payment.queries
  (:require
    [com.repldriven.queenswood.api.cursor :as cursor]
    [com.repldriven.queenswood.api.errors :as errors]

    [com.repldriven.queenswood.payment-query.interface :as payments]

    [com.repldriven.mono.error.interface :as error]))

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

(defn list-inbound-payments
  [request]
  (let [{:keys [auth parameters]} request
        {:keys [bank-id]} auth
        {:keys [status page]} (:query parameters)
        result (payments/list-inbound-payments request bank-id status)]
    (if (error/anomaly? result)
      (errors/anomaly->response result)
      (let [windowed (cursor/window result :payment-id :desc page)]
        {:status 200
         :body (cursor/page-body (cursor/request-path request)
                                 page
                                 (:page windowed)
                                 windowed)}))))