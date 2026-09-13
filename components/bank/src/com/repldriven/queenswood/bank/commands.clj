(ns com.repldriven.queenswood.bank.commands
  (:require
    [com.repldriven.queenswood.bank.core :as core]

    [com.repldriven.mono.avro.interface :as avro]
    [com.repldriven.mono.error.interface :as error :refer [let-nom>]]
    [com.repldriven.mono.processor.interface :as processor]))

(defn- ->response
  [config result]
  (if (error/anomaly? result)
    result
    (let [{:keys [schemas]} config
          {:keys [bank membership owner-invitation-id]} result]
      {:status "ACCEPTED"
       :payload (avro/serialize (schemas "bank")
                                (assoc bank
                                       :membership membership
                                       :owner-invitation-id
                                       owner-invitation-id))})))

(defn- create-bank
  [config data]
  (let [{:keys [name status tier currencies audience company-binding
                membership owner-invitation actor idempotency-key]}
        data]
    (->response config
                (core/new-bank config
                               name
                               status
                               tier
                               currencies
                               {:identity-provider (:identity-provider config)
                                :audience audience
                                :company-binding company-binding
                                :membership membership
                                :owner-invitation owner-invitation
                                :actor actor
                                :idempotency-key idempotency-key}))))

(defn- change-bank-tier
  [config data]
  (let [{:keys [bank-id tier]} data
        result (core/change-tier config bank-id tier)]
    (if (error/anomaly? result)
      result
      (->response config {:bank result}))))

(defn- change-bank-status
  [config data]
  (let [{:keys [bank-id status audience]} data
        result (core/change-status config
                                   bank-id
                                   status
                                   {:identity-provider (:identity-provider
                                                        config)
                                    :audience audience})]
    (if (error/anomaly? result)
      result
      (->response config {:bank result}))))

(def ^:private command-handlers
  {"create-bank" create-bank
   "change-bank-tier" change-bank-tier
   "change-bank-status" change-bank-status})

(defn- dispatch
  [config message]
  (let [{:keys [command id payload]} message
        handler (get command-handlers command)]
    (if (nil? handler)
      (error/reject :bank/unknown-command
                    (str "Unknown command: " command))
      (let [{:keys [schemas]} config
            schema (get schemas command)]
        (if-not schema
          (error/fail :bank/process-command
                      {:message "No schema found for command"
                       :command command})
          (let-nom> [raw (avro/deserialize-same schema payload)
                     data (assoc raw :idempotency-key id)]
            (handler config data)))))))

(defrecord BankProcessor [config]
  processor/Processor
    (process [_ message] (dispatch config message)))
