(ns com.repldriven.queenswood.onfido-adapter.commands
  (:require
    [com.repldriven.queenswood.onfido-relay.interface :as relay]

    [com.repldriven.mono.avro.interface :as avro]
    [com.repldriven.mono.error.interface :as error :refer [let-nom>]]
    [com.repldriven.mono.log.interface :as log]
    [com.repldriven.mono.processor.interface :as processor]
    [com.repldriven.mono.utility.interface :as utility]))

(defn- submit-idv-check-intent
  "Persist the outbound Onfido submission as a pending intent in one FDB
  transaction, then ack. The out-of-transaction runner starts or
  resumes the Onfido workflow run. A redelivered command (same session)
  dedupes at the unique index."
  [config data]
  (let [{:keys [record-db record-store]} config
        fdb-config {:record-db record-db :record-store record-store}
        {:keys [verification-id session-id]} data
        res (relay/save-intent fdb-config
                               {:intent-id (str (utility/uuidv7))
                                :idempotency-key (or session-id verification-id)
                                :kind :onfido-outbound-intent-kind-check
                                :subjects [verification-id]
                                :request (pr-str data)
                                :status :outbound-intent-status-pending
                                :attempt-count 0
                                :created-at (utility/now)})]
    (cond
     (not (error/anomaly? res))
     {:status "ACCEPTED"}

     (relay/uniqueness-violation? res)
     {:status "ACCEPTED"}

     :else
     res)))

(def ^:private command-handlers {"submit-idv-check" submit-idv-check-intent})

(defn- dispatch
  [config message]
  (let [{:keys [command payload]} message
        handler (get command-handlers command)]
    (if (nil? handler)
      (do (log/warnf "Onfido adapter ignoring unknown command: %s" command)
          nil)
      (let [{:keys [schemas]} config
            schema (get schemas command)]
        (if-not schema
          (do (log/warnf "No schema found for command: %s" command)
              nil)
          (let-nom> [data (avro/deserialize-same schema payload)]
            (handler config data)))))))

(defrecord OnfidoCommandProcessor [config]
  processor/Processor
    (process [_ message] (dispatch config message)))
