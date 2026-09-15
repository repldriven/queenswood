(ns com.repldriven.queenswood.email.events
  (:require
    [com.repldriven.queenswood.email.domain :as domain]
    [com.repldriven.queenswood.email.store :as store]

    [com.repldriven.mono.avro.interface :as avro]
    [com.repldriven.mono.error.interface :as error :refer [let-nom>]]
    [com.repldriven.mono.log.interface :as log]
    [com.repldriven.mono.processor.interface :as processor]
    [com.repldriven.mono.utility.interface :as utility]))

(def ^:private invitation-events #{"invitation-created" "invitation-resent"})

(def ^:private invitation-changed-schema "invitation-changed")

(defn- write-once
  "Write the pending delivery, treating the unique index's refusal as
  the no-op it is: a redelivered event already wrote its delivery."
  [config message data]
  (let [delivery (domain/new-invitation-delivery data
                                                 (:id message)
                                                 (utility/now))
        result (store/save-delivery config delivery)]
    (if (store/uniqueness-violation? result)
      (log/info "Email delivery already written for this event"
                {:changelog-event-id (:id message)
                 :invitation-id (:invitation-id data)})
      result)))

(defn- dispatch
  [config message]
  (let [{:keys [event payload]} message
        schema (get (:schemas config) invitation-changed-schema)]
    (cond
     (not (contains? invitation-events event))
     (log/debugf "No email for event: %s" event)

     (nil? schema)
     (error/fail :email/unregistered-schema
                 {:message "Invitation event has no registered payload schema"
                  :event event})

     :else
     (let-nom> [data (avro/deserialize-same schema payload)]
       (write-once config message data)))))

(defrecord EmailEventProcessor [config]
  processor/Processor
    (process [_ message] (dispatch config message)))
