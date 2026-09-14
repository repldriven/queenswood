(ns com.repldriven.queenswood.membership.changelog
  (:require
    [com.repldriven.queenswood.schema.interface :as schema]

    [com.repldriven.mono.avro.interface :as avro]
    [com.repldriven.mono.error.interface :refer [let-nom>]]
    [com.repldriven.mono.telemetry.interface :as telemetry]
    [com.repldriven.mono.utility.interface :as utility]

    [clojure.java.io :as io]))

;; Loaded from the classpath rather than the injected `avro/serde`: the
;; payload schema is a property of this brick, and `store.clj` only ever
;; receives a Txn, never the system config the serde arrives in.
(def ^:private schema
  (delay (avro/json->schema
          (slurp (io/resource
                  "schemas/memberships/invitation-changed.avsc.json")))))

(defn invitation-changed
  "Build the shared-envelope changelog bytes for an invitation that is to
  be emailed. `event-name` is `invitation-created` or
  `invitation-resent`; `invitation` carries `:bank-id`, `:invitation-id`
  and `:expires-at`."
  [event-name {:keys [bank-id invitation-id expires-at]}]
  (let-nom> [payload (avro/serialize @schema
                                     {:bank-id bank-id
                                      :invitation-id invitation-id
                                      :expires-at expires-at})]
    (schema/ChangelogEvent->pb
     (utility/assoc-some
      {:event-id (str (utility/uuidv7))
       :dedup-key (str invitation-id ":" expires-at)
       :event-name event-name
       :payload payload
       :causation-id invitation-id
       :ordering-key invitation-id
       :created-at (utility/now)}
      :traceparent
      (telemetry/inject-traceparent)))))
