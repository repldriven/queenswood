(ns com.repldriven.queenswood.payment.provider
  (:require
    [com.repldriven.queenswood.bank-activity.interface :as bank-activity]
    [com.repldriven.queenswood.bank-query.interface :as bank-query]
    [com.repldriven.queenswood.payment-provider.interface :as
     payment-provider]

    [com.repldriven.mono.avro.interface :as avro]
    [com.repldriven.mono.error.interface :as error :refer [let-nom>]]
    [com.repldriven.mono.telemetry.interface :as telemetry]
    [com.repldriven.mono.utility.interface :as utility]))

(defn- entry
  [config txn bank-id]
  (when-let [providers (:payment-providers config)]
    (let-nom> [bank (bank-query/find-bank txn bank-id)]
      (payment-provider/for-bank providers bank))))

(defn declaration
  [config txn bank-id]
  (error/nom-> (entry config txn bank-id)
               :declaration))

(defn command-channel
  [config txn bank-id]
  (error/nom-> (entry config txn bank-id)
               :command-channel))

(defn send-command
  "Send `command` to the bank's payment provider, keyed by the bank."
  [config bank-id command causation-id data]
  (let [{:keys [bus schemas]} config]
    (let-nom> [channel (command-channel config config bank-id)
               payload (avro/serialize (get schemas command) data)]
      (if (nil? channel)
        (error/fail :payment/no-provider
                    {:message "No payment provider reaches this bank"
                     :bank-id bank-id
                     :command command})
        (bank-activity/send-command bus
                                    channel
                                    bank-id
                                    {:command command
                                     :id (str (utility/uuidv7))
                                     :correlation-id (str (utility/uuidv7))
                                     :causation-id causation-id
                                     :traceparent (telemetry/inject-traceparent)
                                     :payload payload})))))
