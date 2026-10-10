(ns com.repldriven.queenswood.api.payment.queries
  (:require
    [com.repldriven.queenswood.api.cursor :as cursor]
    [com.repldriven.queenswood.api.errors :as errors]

    [com.repldriven.queenswood.payment-api.interface :as payment-api]
    [com.repldriven.queenswood.payment-query.interface :as payments]

    [com.repldriven.mono.error.interface :as error]))

(def ^:private not-found
  {:status 404
   :body (errors/error-response 404 "REJECTED"
                                ":payment/not-found" "Payment not found")})

(defn- payment-response
  [->body result]
  (cond
   (error/anomaly? result)
   (errors/anomaly->response result)

   (nil? result)
   not-found

   :else
   {:status 200 :body (->body result)}))

(defn- read-payment
  [find-payment ->body request]
  (let [{:keys [auth parameters]} request
        {:keys [bank-id]} auth
        {:keys [path]} parameters
        {:keys [payment-id]} path]
    (payment-response ->body (find-payment request bank-id payment-id))))

(defn get-internal-payment
  [request]
  (read-payment payments/find-internal-payment
                payment-api/->internal-body
                request))

(defn get-outbound-payment
  [request]
  (read-payment payments/find-outbound-payment
                payment-api/->outbound-body
                request))

(defn get-inbound-payment
  [request]
  (read-payment payments/find-inbound-payment
                payment-api/->inbound-body
                request))

(defn list-inbound-payments
  [request]
  (let [{:keys [auth parameters]} request
        {:keys [bank-id]} auth
        {:keys [status page]} (:query parameters)
        result (payments/list-inbound-payments request bank-id status)]
    (if (error/anomaly? result)
      (errors/anomaly->response result)
      (let [windowed (update (cursor/window result :payment-id :desc page)
                             :page
                             (fn [items]
                               (mapv payment-api/->inbound-body items)))]
        {:status 200
         :body (cursor/page-body (cursor/request-path request)
                                 page
                                 (:page windowed)
                                 windowed)}))))