(ns com.repldriven.queenswood.changelog-relay.envelope
  (:require
    [com.repldriven.queenswood.schema.interface :as schema]

    [com.repldriven.mono.error.interface :as error]
    [com.repldriven.mono.event.interface :as event]
    [com.repldriven.mono.log.interface :as log]
    [com.repldriven.mono.utility.interface :refer [assoc-some]]))

(defn ->handler
  [{:keys [bus event-channel store-name]}]
  (fn [_ctx changelog-bytes]
    (let [decoded (error/try-nom :changelog-relay/decode
                                 "Changelog entry is not a shared envelope"
                                 (schema/pb->ChangelogEvent changelog-bytes))]
      (if (error/anomaly? decoded)
        ;; Skip rather than throw. A throw holds the cursor, so an entry
        ;; written before the store adopted the envelope would redrive
        ;; forever and block every later entry behind it.
        (log/error "Skipping undecodable changelog entry"
                   {:store-name store-name :anomaly decoded})
        (let [{:keys [event-id event-name payload correlation-id causation-id
                      traceparent ordering-key]}
              decoded
              envelope (-> (event/envelope event-name
                                           causation-id
                                           correlation-id)
                           (assoc :payload payload)
                           ;; The writer's span, so the consumer's
                           ;; `process-event` joins that trace rather than
                           ;; opening its own. Absent for entries written
                           ;; before the field existed, or with nothing
                           ;; being traced.
                           (assoc-some :traceparent traceparent)
                           ;; `event/envelope` mints `:id` per publish; the
                           ;; relay overrides it with the entry's id, so a
                           ;; redrive republishes under the same
                           ;; identifier.
                           (assoc-some :id event-id))
              ;; The writer declares the ordering key; the relay only
              ;; carries it across. Absent means an unkeyed publish —
              ;; correct while a topic has one partition, and the thing
              ;; that reorders once it does not.
              res (event/publish bus
                                 envelope
                                 (assoc-some
                                  {:event-channel event-channel}
                                  :key
                                  ordering-key))]
          (when (error/anomaly? res)
            (log/error "Changelog relay publish failed; will redrive" res)
            ;; nosemgrep: no-raw-throw
            (throw (ex-info "Changelog relay publish failed"
                            {:anomaly res}))))))))
