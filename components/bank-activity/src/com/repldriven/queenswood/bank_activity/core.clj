(ns com.repldriven.queenswood.bank-activity.core
  (:require
    [com.repldriven.queenswood.bank-activity.store :as store]

    [com.repldriven.queenswood.schema.interface :as schema]

    [com.repldriven.mono.avro.interface :as avro]
    [com.repldriven.mono.error.interface :as error :refer [let-nom>]]
    [com.repldriven.mono.log.interface :as log]
    [com.repldriven.mono.message-bus.interface :as message-bus]
    [com.repldriven.mono.telemetry.interface :as telemetry]
    [com.repldriven.mono.utility.interface :as utility]

    [clojure.java.io :as io]))

(def shard-count 4)

(def ^:private
     ^{:doc "How many times a send is tried before it fails."} send-attempts
  5)

(def ^:private
     ^{:doc "The wait before a send's next try, times its count."}
     send-backoff-ms
  200)

(def ^:private schema-paths
  {"account-opening" "schemas/bank-activity/account-opening.avsc.json"
   "account-closing" "schemas/bank-activity/account-closing.avsc.json"
   "account-address-rotation-requested"
   "schemas/bank-activity/account-address-rotation-requested.avsc.json"
   "outbound-payment-submitted"
   "schemas/bank-activity/outbound-payment-submitted.avsc.json"
   "inbound-payment-suspended"
   "schemas/bank-activity/inbound-payment-suspended.avsc.json"
   "idv-session-opening" "schemas/bank-activity/idv-session-opening.avsc.json"
   "transaction-posted" "schemas/transactions/transaction-posted.avsc.json"})

(def ^:private schemas
  (delay (update-vals schema-paths
                      (fn [path]
                        (avro/json->schema (slurp (io/resource path)))))))

(defn shard-log
  [n]
  (str "bank-activity-" n))

(defn shard-of
  [^String bank-id]
  (Math/floorMod (.hashCode bank-id) (int shard-count)))

(defn log-name
  [bank-id]
  (shard-log (shard-of bank-id)))

(defn record
  [txn {:keys [bank-id event-name data causation-id dedup-key]}]
  (if-let [s (get @schemas event-name)]
    (let-nom> [payload (avro/serialize s (assoc data :bank-id bank-id))
               entry (schema/ChangelogEvent->pb
                      (utility/assoc-some {:event-id (str (utility/uuidv7))
                                           :dedup-key (str event-name
                                                           ":"
                                                           dedup-key)
                                           :event-name event-name
                                           :payload payload
                                           :causation-id causation-id
                                           :ordering-key bank-id
                                           :created-at (utility/now)}
                                          :traceparent
                                          (telemetry/inject-traceparent)))]
      (store/write-entry txn (log-name bank-id) causation-id entry))
    (error/fail :bank-activity/unknown-event
                {:message "No activity schema has this name"
                 :event-name event-name})))

(defn send-command
  [bus channel bank-id command]
  (loop [n 1]
    (let [res (message-bus/send bus channel command {:key bank-id})]
      (if (and (error/anomaly? res) (< n send-attempts))
        (do (log/warn "Provider command send failed; trying again"
                      {:command (:command command) :attempt n :anomaly res})
            (Thread/sleep (long (* n send-backoff-ms)))
            (recur (inc n)))
        res))))
