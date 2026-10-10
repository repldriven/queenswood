(ns com.repldriven.queenswood.idv.commands
  (:require
    [com.repldriven.queenswood.idv.core :as core]

    [com.repldriven.queenswood.schema.interface :as schema]

    [com.repldriven.mono.avro.interface :as avro]
    [com.repldriven.mono.error.interface :as error :refer [let-nom>]]
    [com.repldriven.mono.processor.interface :as processor]))

(defn- open-idv-session
  [config data]
  (let [result (core/open-session config data)]
    (if (error/anomaly? result)
      result
      (let [{:keys [schemas]} config]
        {:status "ACCEPTED"
         :payload (avro/serialize (schemas "idv-command-reply")
                                  {:record (schema/IdvSession->pb
                                            result)})}))))

(def ^:private command-handlers {"open-idv-session" open-idv-session})

(defn- dispatch
  [config message]
  (let [{:keys [command causation-id payload]} message
        handler (get command-handlers command)]
    (if (nil? handler)
      (error/reject :idv/unknown-command
                    (str "Unknown command: " command))
      (let [{:keys [schemas]} config
            schema (get schemas command)]
        (if-not schema
          (error/fail :idv/process-command
                      {:message "No schema found for command"
                       :command command})
          (let-nom> [raw (avro/deserialize-same schema payload)]
            (handler config (assoc raw :request-id causation-id))))))))

(defrecord IdvProcessor [config]
  processor/Processor
    (process [_ message] (dispatch config message)))
