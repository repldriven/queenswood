(ns com.repldriven.queenswood.cash-account.changelog
  (:require
    [com.repldriven.queenswood.schema.interface :as schema]

    [com.repldriven.mono.avro.interface :as avro]
    [com.repldriven.mono.error.interface :as error :refer [let-nom>]]
    [com.repldriven.mono.telemetry.interface :as telemetry]
    [com.repldriven.mono.utility.interface :as utility]

    [clojure.java.io :as io]))

(def ^:private event-name "cash-account-status-changed")

;; Loaded from the classpath rather than the injected `avro/serde`: the
;; payload schema is a property of this brick, and `store.clj` only ever
;; receives a Txn, never the system config the serde arrives in.
(def ^:private schema
  (delay (avro/json->schema
          (slurp (io/resource
                  "schemas/cash-accounts/account-status-changed.avsc.json")))))

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
      (schema/ChangelogEvent->pb
       (utility/assoc-some
        {:event-id (str (utility/uuidv7))
         :dedup-key (str account-id ":" (name change-kind) ":" updated-at)
         :event-name event-name
         :payload payload
         :causation-id account-id
         :ordering-key account-id
         :created-at (utility/now)}
        ;; Written inside the command's transaction, so this is the
        ;; `process-command` span — which is itself under the request. The
        ;; relay republishes it and the consumer's span joins that trace.
        :traceparent
        (telemetry/inject-traceparent))))))
