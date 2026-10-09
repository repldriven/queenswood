(ns com.repldriven.queenswood.payment.commands
  (:require
    [com.repldriven.queenswood.payment.core :as core]
    [com.repldriven.queenswood.payment.events.activity :as activity]
    [com.repldriven.queenswood.payment.events.inbound :as inbound]
    [com.repldriven.queenswood.payment.events.outbound :as outbound]
    [com.repldriven.queenswood.payment.events.provider-transfer :as
     provider-transfer]

    [com.repldriven.mono.avro.interface :as avro]
    [com.repldriven.mono.error.interface :as error :refer [let-nom>]]
    [com.repldriven.mono.processor.interface :as processor]))

(defn- ->response
  [config schema-name result]
  (if (error/anomaly? result)
    result
    (let [{:keys [schemas]} config]
      (let-nom> [payload (avro/serialize (schemas schema-name) result)]
        {:status "ACCEPTED" :payload payload}))))

(def ^:private command-handlers
  {"submit-internal-payment"
   (fn [config data]
     (->response config "internal-payment" (core/submit-internal config data)))
   "admit-inbound-payment" (fn [config data]
                             (->response config
                                         "admit-inbound-payment-reply"
                                         (inbound/admit-inbound config data)))
   "submit-outbound-payment" (fn [config data]
                               (->response config
                                           "outbound-payment"
                                           (core/submit-outbound config
                                                                 data)))})

(defn- dispatch
  [config message]
  (let [{:keys [command id payload]} message
        handler (get command-handlers command)]
    (if (nil? handler)
      (error/reject :payment/unknown-command
                    (str "Unknown command: " command))
      (let [{:keys [schemas]} config
            schema (get schemas command)]
        (if-not schema
          (error/fail :payment/process-command
                      {:message "No schema found for command"
                       :command command})
          (let-nom> [raw (avro/deserialize-same schema payload)
                     data (assoc raw :idempotency-key id)]
            (handler config data)))))))

(defrecord PaymentProcessor [config]
  processor/Processor
    (process [_ message] (dispatch config message)))

(defn- dispatch-event
  [config message]
  (let [{:keys [event payload]} message
        {:keys [schemas]} config
        schema (get schemas event)]
    (if-not schema
      (error/fail :payment/schema-not-found
                  {:message "Event schema not found"
                   :event event})
      (let-nom> [data (avro/deserialize-same schema payload)
                 {:keys [debit-credit-code]} data]
        (case event
          "provider-payment-settled"
          (case debit-credit-code
            :debit-credit-code-credit
            (inbound/settle-inbound config data)

            :debit-credit-code-debit
            (outbound/settle-outbound config data)

            (error/fail :payment/unknown-debit-credit-code
                        {:message "Unknown debit-credit-code"
                         :debit-credit-code debit-credit-code}))

          "provider-payment-held"
          (case debit-credit-code
            :debit-credit-code-credit
            (inbound/hold-inbound config data)

            :debit-credit-code-debit
            (outbound/hold-outbound config data)

            (error/fail :payment/unknown-debit-credit-code
                        {:message "Unknown debit-credit-code"
                         :debit-credit-code debit-credit-code}))

          "provider-payment-rejected"
          (case debit-credit-code
            :debit-credit-code-credit
            (inbound/return-inbound config data)

            ;; Outbound declines default to debit; treat an absent/unknown
            ;; code as the outbound path for backward compatibility.
            (outbound/reject-outbound config data))

          "provider-payment-returned"
          (case debit-credit-code
            :debit-credit-code-credit
            (inbound/return-suspended config data)

            :debit-credit-code-debit
            (outbound/return-outbound config data)

            (error/fail :payment/unknown-debit-credit-code
                        {:message "Unknown debit-credit-code"
                         :debit-credit-code debit-credit-code}))

          "inbound-return-failed"
          (inbound/return-failed config data)

          "transfer-completed"
          (provider-transfer/complete-transfer config data)

          "transfer-failed"
          (provider-transfer/fail-transfer config data)

          (error/fail :payment/unknown-event
                      {:message "Unknown event"
                       :event event}))))))

(defrecord PaymentEventProcessor [config]
  processor/Processor
    (process [_ message] (dispatch-event config message)))

(defrecord ActivityEventProcessor [config]
  processor/Processor
    (process [_ message] (activity/handle config message)))
