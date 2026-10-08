(ns com.repldriven.queenswood.zyphe-adapter.commands
  (:require
    [com.repldriven.queenswood.zyphe-relay.interface :as relay]

    [com.repldriven.mono.avro.interface :as avro]
    [com.repldriven.mono.error.interface :as error :refer [let-nom>]]
    [com.repldriven.mono.log.interface :as log]
    [com.repldriven.mono.processor.interface :as processor]
    [com.repldriven.mono.utility.interface :as utility]))

(defn- submit-idv-check-intent
  "Persist the outbound Zyphe submission as a pending intent in one FDB
  transaction, then ack. The out-of-transaction runner creates or
  resumes the Zyphe verification request. A redelivered command (same
  session) dedupes at the unique index."
  [config data]
  (let [{:keys [record-db record-store]} config
        fdb-config {:record-db record-db :record-store record-store}
        {:keys [verification-id session-id]} data
        res (relay/save-intent fdb-config
                               {:intent-id (str (utility/uuidv7))
                                :idempotency-key (or session-id verification-id)
                                :kind :zyphe-outbound-intent-kind-check
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

(defn- command-data
  "The command's handler and its decoded data, nil for a command this
  adapter does not handle, or an anomaly."
  [config message]
  (let [{:keys [command payload]} message
        handler (get command-handlers command)
        schema (get (:schemas config) command)]
    (cond
     (nil? handler)
     (log/warnf "Zyphe adapter ignoring unknown command: %s" command)

     (nil? schema)
     (log/warnf "No schema found for command: %s" command)

     :else
     (let-nom> [data (avro/deserialize-same schema payload)]
       [handler data]))))

(defn- dispatch
  [config message]
  (let-nom> [[handler data] (command-data config message)]
    (when handler (handler config data))))

(defrecord ZypheCommandProcessor [config]
  processor/Processor
    (process [_ message] (dispatch config message))
  processor/Keyed
    (performer-key [_ message]
      (let [found (command-data config message)]
        (when-not (error/anomaly? found) (:verification-id (second found))))))
