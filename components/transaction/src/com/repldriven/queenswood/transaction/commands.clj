(ns com.repldriven.queenswood.transaction.commands
  (:require
    [com.repldriven.queenswood.transaction.core :as core]

    [com.repldriven.mono.avro.interface :as avro]
    [com.repldriven.mono.error.interface :as error :refer [let-nom>]]
    [com.repldriven.mono.processor.interface :as processor]))

(defn- ->response
  [config result]
  (if (error/anomaly? result)
    result
    (let [{:keys [schemas]} config]
      (let-nom> [payload (avro/serialize (schemas "transaction") result)]
        {:status "ACCEPTED" :payload payload}))))

(def ^:private command-handlers
  {"record-transaction"
   (fn [config data]
     (->response config (core/record-and-post config (:bank-id data) data)))})

(defn- dispatch
  [config message]
  (let [{:keys [command id causation-id payload]} message
        handler (get command-handlers command)]
    (if (nil? handler)
      (error/reject :transaction/unknown-command
                    (str "Unknown command: " command))
      (let [{:keys [schemas]} config
            schema (get schemas command)]
        (if-not schema
          (error/fail :transaction/process-command
                      {:message "No schema found for command"
                       :command command})
          (let-nom> [raw (avro/deserialize-same schema payload)
                     data (assoc raw
                                 :idempotency-key id
                                 :request-id causation-id)]
            (handler config data)))))))

(defrecord TransactionProcessor [config]
  processor/Processor
    (process [_ message] (dispatch config message)))
