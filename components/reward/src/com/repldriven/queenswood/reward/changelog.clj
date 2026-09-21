(ns com.repldriven.queenswood.reward.changelog
  (:require
    [com.repldriven.queenswood.schema.interface :as schema]

    [com.repldriven.mono.avro.interface :as avro]
    [com.repldriven.mono.error.interface :as error :refer [let-nom>]]
    [com.repldriven.mono.telemetry.interface :as telemetry]
    [com.repldriven.mono.utility.interface :as utility]

    [clojure.java.io :as io]))

(def ^:private event-name "reward-status-changed")

;; Loaded from the classpath rather than the injected `avro/serde`: the
;; payload schema is a property of this brick, and `store.clj` only ever
;; receives a Txn, never the system config the serde arrives in.
(def ^:private payload-schema
  (delay (avro/json->schema
          (slurp (io/resource
                  "schemas/rewards/reward-status-changed.avsc.json")))))

(defn status-changed
  "The shared-envelope changelog bytes for a reward write. `changelog`
  carries `:bank-id`, `:reward-id`, `:account-id`, `:status-before`,
  `:status-after`, `:change-kind` and `:updated-at`; `store.clj`
  supplies everything but the kind and the status before off the saved
  row, and the caller passes those two.

  A missing `:change-kind` is an error anomaly rather than a null in
  the payload: a consumer telling a pay from a defer reads the kind,
  not the pair of statuses."
  [{:keys [bank-id reward-id account-id status-before status-after
           change-kind updated-at]}]
  (if-not change-kind
    (error/fail :reward/changelog
                {:message "Reward changelog entry needs a change kind"
                 :reward-id reward-id
                 :status-after status-after})
    (let-nom> [payload (avro/serialize @payload-schema
                                       {:bank-id bank-id
                                        :reward-id reward-id
                                        :account-id account-id
                                        :status-before status-before
                                        :status-after status-after
                                        :change-kind change-kind})]
      (schema/ChangelogEvent->pb
       (utility/assoc-some
        {:event-id (str (utility/uuidv7))
         :dedup-key (str reward-id ":" (name change-kind) ":" updated-at)
         :event-name event-name
         :payload payload
         :causation-id reward-id
         :ordering-key reward-id
         :created-at (utility/now)}
        ;; Written inside the paying transaction, so this is the
        ;; processing span. The relay republishes it and the consumer's
        ;; span joins that trace.
        :traceparent
        (telemetry/inject-traceparent))))))
