(ns com.repldriven.queenswood.party.commands
  (:require
    [com.repldriven.queenswood.party.core :as core]

    [com.repldriven.queenswood.schema.interface :as schema]

    [com.repldriven.mono.avro.interface :as avro]
    [com.repldriven.mono.error.interface :as error :refer [let-nom>]]
    [com.repldriven.mono.processor.interface :as processor]))

(defn- ->response
  [config result]
  (if (error/anomaly? result)
    result
    (let [{:keys [schemas]} config]
      {:status "ACCEPTED"
       :payload (avro/serialize (schemas "party") (schema/pb->Party result))})))

(def ^:private command-handlers
  {"create-party" (fn [config data]
                    (->response config (core/new-party config data)))
   "merge-party" (fn [config data]
                   (->response config (core/merge-party config data)))
   "suspend-party" (fn [config data]
                     (->response config (core/suspend-party config data)))
   "resume-party" (fn [config data]
                    (->response config (core/resume-party config data)))
   "close-party" (fn [config data]
                   (->response config (core/close-party config data)))})

(defn- dispatch
  [config message]
  (let [{:keys [command id payload]} message
        handler (get command-handlers command)]
    (if (nil? handler)
      (error/reject :party/unknown-command
                    (str "Unknown command: " command))
      (let [{:keys [schemas]} config
            schema (get schemas command)]
        (if-not schema
          (error/fail :party/process-command
                      {:message "No schema found for command"
                       :command command})
          (let-nom> [raw (avro/deserialize-same schema payload)
                     data (assoc raw :idempotency-key id)]
            (handler config data)))))))

(defrecord PartyProcessor [config]
  processor/Processor
    (process [_ message] (dispatch config message)))
