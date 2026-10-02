(ns com.repldriven.queenswood.transaction.changelog
  (:require
    [com.repldriven.queenswood.schema.interface :as schema]

    [com.repldriven.mono.avro.interface :as avro]
    [com.repldriven.mono.error.interface :refer [let-nom>]]
    [com.repldriven.mono.telemetry.interface :as telemetry]
    [com.repldriven.mono.utility.interface :as utility]

    [clojure.java.io :as io]))

(def ^:private event-name "transaction-posted")

;; Loaded from the classpath rather than the injected `avro/serde`: the
;; payload schema is a property of this brick, and `store.clj` only ever
;; receives a Txn, never the system config the serde arrives in.
(def ^:private posted-schema
  (delay (avro/json->schema
          (slurp (io/resource
                  "schemas/transactions/transaction-posted.avsc.json")))))

(defn- ->leg
  [{:keys [account-id balance-type balance-status side amount control]}]
  (utility/assoc-some {:account-id account-id
                       :balance-type balance-type
                       :balance-status balance-status
                       :side side
                       :amount amount}
                      :control
                      control))

(defn posted-data
  [transaction legs scheme-account-id]
  (let [{:keys [transaction-id bank-id transaction-type currency]} transaction]
    (utility/assoc-some {:bank-id bank-id
                         :transaction-id transaction-id
                         :transaction-type transaction-type
                         :currency currency
                         :legs (mapv ->leg legs)}
                        :scheme-account-id
                        scheme-account-id)))

(defn posted
  "The shared-envelope changelog bytes for a recorded transaction, keyed
  and ordered by its bank so a consumer sees one bank's postings in the
  order they committed."
  [transaction legs scheme-account-id]
  (let [{:keys [transaction-id bank-id]} transaction]
    (let-nom> [payload (avro/serialize @posted-schema
                                       (posted-data transaction
                                                    legs
                                                    scheme-account-id))]
      (schema/ChangelogEvent->pb
       (utility/assoc-some {:event-id (str (utility/uuidv7))
                            :dedup-key (str transaction-id ":posted")
                            :event-name event-name
                            :payload payload
                            :causation-id transaction-id
                            :ordering-key bank-id
                            :created-at (utility/now)}
                           :traceparent
                           (telemetry/inject-traceparent))))))
