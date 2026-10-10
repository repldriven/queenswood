(ns com.repldriven.queenswood.payee-check.commands
  (:require
    [com.repldriven.queenswood.payee-check.core :as core]

    [com.repldriven.mono.avro.interface :as avro]
    [com.repldriven.mono.error.interface :as error :refer [let-nom>]]
    [com.repldriven.mono.processor.interface :as processor]))

(defn- ->response
  [config result]
  (if (error/anomaly? result)
    result
    (let [{:keys [schemas]} config]
      {:status "ACCEPTED"
       :payload (avro/serialize (schemas "payee-check") result)})))

(def ^:private command-handlers
  {"check-payee" (fn [config data]
                   (->response config (core/check-and-save config data)))})

(defn- dispatch
  [config message]
  (let [{:keys [command causation-id payload]} message
        handler (get command-handlers command)]
    (if (nil? handler)
      (error/reject :payee-check/unknown-command
                    (str "Unknown command: " command))
      (let [{:keys [schemas]} config
            schema (get schemas command)]
        (if-not schema
          (error/fail :payee-check/process-command
                      {:message "No schema found for command"
                       :command command})
          (let-nom> [raw (avro/deserialize-same schema payload)]
            (handler config (assoc raw :request-id causation-id))))))))

(defrecord PayeeCheckProcessor [config]
  processor/Processor
    (process [_ message] (dispatch config message)))
