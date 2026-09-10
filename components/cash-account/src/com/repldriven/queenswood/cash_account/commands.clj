(ns com.repldriven.queenswood.cash-account.commands
  (:require
    [com.repldriven.queenswood.cash-account.core :as core]

    [com.repldriven.queenswood.cash-account-query.interface :as q]

    [com.repldriven.mono.avro.interface :as avro]
    [com.repldriven.mono.error.interface :as error :refer [let-nom>]]
    [com.repldriven.mono.processor.interface :as processor]))

(defn- ->response
  [config result]
  (if (error/anomaly? result)
    result
    (let [{:keys [schemas]} config]
      {:status "ACCEPTED"
       :payload (avro/serialize (schemas "cash-account") result)})))

(def ^:private command-handlers
  {"open-cash-account" (fn [config data]
                         (->response config (core/open-account config data)))
   "close-cash-account" (fn [config data]
                          (->response config (core/close-account config data)))
   "suspend-cash-account"
   (fn [config data] (->response config (core/suspend-account config data)))
   "resume-cash-account"
   (fn [config data] (->response config (core/resume-account config data)))
   "rotate-cash-account-address"
   (fn [config data] (->response config (core/rotate-address config data)))
   "get-cash-account"
   (fn [config data]
     (let [{:keys [bank-id account-id]} data]
       (->response config (q/get-account config bank-id account-id))))})

(defn- dispatch
  [config message]
  (let [{:keys [command id payload]} message
        handler (get command-handlers command)]
    (if (nil? handler)
      (error/reject :cash-account/unknown-command
                    (str "Unknown command: " command))
      (let [{:keys [schemas]} config
            schema (get schemas command)]
        (if-not schema
          (error/fail :cash-account/process-command
                      {:message "No schema found for command"
                       :command command})
          (let-nom> [raw (avro/deserialize-same schema payload)
                     data (assoc raw :idempotency-key id)]
            (handler config data)))))))

(defrecord CashAccountProcessor [config]
  processor/Processor
    (process [_ message] (dispatch config message)))
