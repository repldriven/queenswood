(ns com.repldriven.queenswood.zyphe-adapter.publisher
  "Maps a verified Zyphe webhook event to an `idv-completed` bus-event
  descriptor `{:event-name :dedup-key :data}`. The run's `flow.status`
  maps to a bank-idv status, and `flow.customData` carries the bank and
  verification ids the relay attached when it created the run. The
  webhook handler persists the descriptor to the outbox; the relay
  publishes it.")

(def ^:private flow-status->idv-status
  {"COMPLETED" "ACCEPTED"
   "FAILED" "REJECTED"
   "REJECTED" "REJECTED"
   "REVIEW" "IN_REVIEW"
   "CANCELLED" "FAILED"})

(defn ->idv-completed
  "The idv-completed event descriptor for `event`, or nil when it reports
  no decision — a run still `PROCESSING`, an event that carries no flow
  status, or one whose custom data names no verification. `dedup-key`
  is the verification and the status it reached, so a redelivered event
  does not double-enqueue while a review followed by a decision records
  both."
  [event]
  (let [{:keys [flow]} event
        {:keys [status customData]} flow
        {:keys [bankId verificationId]} customData
        idv-status (get flow-status->idv-status status)]
    (when (and idv-status bankId verificationId)
      {:event-name "idv-completed"
       :dedup-key (str verificationId ":" idv-status)
       :data {:bank-id bankId
              :verification-id verificationId
              :status idv-status}})))
