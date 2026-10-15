(ns com.repldriven.queenswood.cash-account.changelog
  (:require
    [com.repldriven.queenswood.schema.interface :as schema]

    [com.repldriven.mono.avro.interface :as avro]
    [com.repldriven.mono.error.interface :as error :refer [let-nom>]]
    [com.repldriven.mono.telemetry.interface :as telemetry]
    [com.repldriven.mono.utility.interface :as utility]

    [clojure.java.io :as io]))

(def ^:private event-name "cash-account-status-changed")

(def ^:private operation-event-name "cash-account-operation-changed")

;; Loaded from the classpath rather than the injected `avro/serde`: the
;; payload schema is a property of this brick, and `store.clj` only ever
;; receives a Txn, never the system config the serde arrives in.
(def ^:private schema
  (delay (avro/json->schema
          (slurp
           (io/resource
            "schemas/cash-account/cash-account-status-changed.avsc.json")))))

(def ^:private operation-schema
  (delay (avro/json->schema
          (slurp
           (io/resource
            "schemas/cash-account/cash-account-operation-changed.avsc.json")))))

(defn- envelope
  [event payload dedup-key causation-id]
  (schema/ChangelogEvent->pb
   (utility/assoc-some
    {:event-id (str (utility/uuidv7))
     :dedup-key dedup-key
     :event-name event
     :payload payload
     :causation-id causation-id
     :ordering-key causation-id
     :created-at (utility/now)}
    ;; Written inside the command's transaction, so this is the
    ;; `process-command` span — which is itself under the request. The
    ;; relay republishes it and the consumer's span joins that trace.
    :traceparent
    (telemetry/inject-traceparent))))

(defn account-changed
  "Build the shared-envelope changelog bytes for a cash-account write.
  `changelog` carries `:bank-id`, `:account-id`, `:status-before`,
  `:status-after`, `:change-kind` and `:updated-at`; `store.clj` supplies
  `:bank-id` and `:updated-at` off the saved record, and the caller
  passes the kind.

  A missing `:change-kind` is an error anomaly rather than a null in the
  payload: a migration and a rotation both leave the status alone, so the
  kind is the only thing telling a consumer which write it is reading."
  [{:keys [bank-id account-id status-before status-after change-kind
           updated-at]}]
  (if-not change-kind
    (error/fail :cash-account/changelog
                {:message "Cash-account changelog entry needs a change kind"
                 :account-id account-id
                 :status-after status-after})
    (let-nom> [payload (avro/serialize @schema
                                       {:bank-id bank-id
                                        :account-id account-id
                                        :status-before status-before
                                        :status-after status-after
                                        :change-kind change-kind})]
      (envelope event-name
                payload
                (str account-id ":" (name change-kind) ":" updated-at)
                account-id))))

(defn operation-changed
  "Build the shared-envelope changelog bytes for an account operation's
  write, from the operation as saved and its status before, nil when it
  is new. Ordered by its account, so an account's operations reach a
  consumer in the order they changed."
  [operation status-before]
  (let [{:keys [bank-id account-id operation-id kind status]} operation]
    (let-nom> [payload (avro/serialize @operation-schema
                                       {:bank-id bank-id
                                        :account-id account-id
                                        :operation-id operation-id
                                        :kind kind
                                        :status-before status-before
                                        :status-after status})]
      (envelope operation-event-name
                payload
                (str operation-id ":" (name status))
                account-id))))
