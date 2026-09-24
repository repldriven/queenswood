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
    (let [{:keys [schemas]} config
          ;; An account that has never been rotated carries no
          ;; `:retired-payment-addresses` key, and the reply schema's
          ;; array field has no null branch to fall back on.
          account (update result :retired-payment-addresses #(or % []))]
      {:status "ACCEPTED"
       :payload (avro/serialize (schemas "cash-account") account)})))

(defn- get-account
  [config data]
  (let [{:keys [bank-id account-id]} data]
    (q/get-account config bank-id account-id)))

(def ^:private command-fns
  {"open-cash-account" core/open-account
   "close-cash-account" core/close-account
   "suspend-cash-account" core/suspend-account
   "resume-cash-account" core/resume-account
   "rotate-cash-account-address" core/rotate-address
   "get-cash-account" get-account})

(defn- decode
  [schema message]
  (let [{:keys [id payload]} message]
    (let-nom> [raw (avro/deserialize-same schema payload)]
      (assoc raw :idempotency-key id))))

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
