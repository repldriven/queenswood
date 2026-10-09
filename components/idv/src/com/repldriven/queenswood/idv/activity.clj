(ns com.repldriven.queenswood.idv.activity
  (:require
    [com.repldriven.queenswood.idv.core :as core]

    [com.repldriven.queenswood.bank-activity.interface :as bank-activity]

    [com.repldriven.mono.avro.interface :as avro]
    [com.repldriven.mono.error.interface :as error :refer [let-nom>]]
    [com.repldriven.mono.telemetry.interface :as telemetry]
    [com.repldriven.mono.utility.interface :as utility]))

(defn- send-check
  [config data]
  (let [{:keys [bus schemas]} config
        {:keys [bank-id session-id]} data]
    (let-nom> [provider (core/bank-provider config config bank-id)
               payload (avro/serialize (get schemas "submit-idv-check") data)]
      (if-let [channel (:command-channel provider)]
        (bank-activity/send-command bus
                                    channel
                                    bank-id
                                    {:command "submit-idv-check"
                                     :id (str (utility/uuidv7))
                                     :correlation-id (str (utility/uuidv7))
                                     :causation-id session-id
                                     :traceparent (telemetry/inject-traceparent)
                                     :payload payload})
        (error/fail :idv/no-provider
                    {:message
                     "No identity verification provider reaches this bank"
                     :bank-id bank-id})))))

(defn handle
  [config message]
  (let [{:keys [event payload]} message]
    (when (= "idv-session-open-requested" event)
      (let-nom> [data (avro/deserialize-same (get (:schemas config) event)
                                             payload)]
        (send-check config data)))))
