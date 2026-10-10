(ns com.repldriven.queenswood.cash-account.commands
  (:require
    [com.repldriven.queenswood.cash-account.core :as core]

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
       :payload (avro/serialize (schemas "cash-account-command-reply")
                                {:record (schema/CashAccount->pb result)})})))

(def ^:private command-fns
  {"open-cash-account" (fn [config data]
                         (core/open-account config data {:audited? true}))
   "close-cash-account" core/close-account
   "suspend-cash-account" core/suspend-account
   "resume-cash-account" core/resume-account
   "rotate-cash-account-address" core/rotate-address})

(defn- decode
  [schema message]
  (let [{:keys [id causation-id payload]} message]
    (let-nom> [raw (avro/deserialize-same schema payload)]
      (assoc raw :idempotency-key id :request-id causation-id))))

(defn- dispatch
  [config message]
  (let [{:keys [command]} message
        f (get command-fns command)]
    (if (nil? f)
      (error/reject :cash-account/unknown-command
                    (str "Unknown command: " command))
      (let [{:keys [schemas]} config
            schema (get schemas command)]
        (if-not schema
          (error/fail :cash-account/process-command
                      {:message "No schema found for command"
                       :command command})
          (let-nom> [data (decode schema message)]
            (->response config (f config data))))))))

(defrecord CashAccountProcessor [config]
  processor/Processor
    (process [_ message] (dispatch config message)))
