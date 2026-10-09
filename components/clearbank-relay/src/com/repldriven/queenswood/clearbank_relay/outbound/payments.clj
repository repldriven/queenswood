(ns com.repldriven.queenswood.clearbank-relay.outbound.payments
  (:require
    [com.repldriven.queenswood.clearbank-relay.outbound.shared :as shared]

    [com.repldriven.queenswood.intent-poller.interface :as intent-poller]

    [com.repldriven.mono.error.interface :as error]
    [com.repldriven.mono.http-client.interface :as http]))

(defn- transaction-response
  [res end-to-end-id]
  (let [body (http/res->edn res)]
    (when (map? body)
      (some (fn [{:keys [endToEndIdentification response]}]
              (when (= end-to-end-id endToEndIdentification) response))
            (:transactions body)))))

(defn- classify
  [res end-to-end-id]
  (let [status (:status res)]
    (cond
     (error/anomaly? res)
     [:retry (:message (error/payload res))]

     (not (int? status))
     [:retry "no HTTP status"]

     (or (<= 500 status) (= 408 status) (= 429 status))
     [:retry (str "HTTP " status)]

     (<= 400 status 499)
     [:refused (str "HTTP " status)]

     (<= 200 status 299)
     (let [response (transaction-response res end-to-end-id)]
       (cond
        (= "Accepted" response)
        [:answered nil]

        (some? response)
        [:refused (str "HTTP " status " response " response)]

        :else
        [:refused
         (str "HTTP " status
              " with no response for "
              end-to-end-id)]))

     :else
     [:retry (str "HTTP " status)])))

(defn- rejected
  [intent failure-kind reason now]
  {:event-name "provider-payment-rejected"
   :dedup-key (str (:idempotency-key intent) ":submission-rejected")
   :data {:end-to-end-id (:idempotency-key intent)
          :scheme "fps"
          :debit-credit-code :debit-credit-code-debit
          :cancellation-code "NARR"
          :failure-kind failure-kind
          :reason-code "NARR"
          :cancellation-reason reason
          :is-return false
          :timestamp-rejected now}})

(defn- pay
  "The outbound FPS call. A retried POST is safe — ClearBank dedupes on
  endToEndIdentification — which is what lets the poller retry at all."
  [config _now intent]
  (let [{:keys [idempotency-key request]} intent]
    (classify (shared/post config "/v3/payments/fps" request)
              idempotency-key)))

(defn- paid
  "An accepted submission leaves the payment sent, for the settlement
  webhook to complete."
  [_config _now _intent _result]
  {:status :outbound-intent-status-sent})

(defn- payment-failed
  [_config now intent failure reason]
  {:status :outbound-intent-status-failed
   :event (rejected intent
                    (if (= :refused failure)
                      :failure-kind-refused
                      :failure-kind-undelivered)
                    reason
                    now)})

(intent-poller/defoperations
 :clearbank
 {:clearbank-outbound-intent-kind-payment
  {:call pay :answered paid :failed payment-failed}})
