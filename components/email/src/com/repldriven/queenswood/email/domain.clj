(ns com.repldriven.queenswood.email.domain
  (:require
    [com.repldriven.queenswood.circuit-breaker.interface :as circuit-breaker]

    [com.repldriven.mono.utility.interface :as utility]))

(def ^:private pending :email-delivery-status-pending)
(def ^:private sent :email-delivery-status-sent)
(def ^:private superseded :email-delivery-status-superseded)
(def ^:private failed :email-delivery-status-failed)

(def ^:private invitation-pending :invitation-status-pending)

(defn new-invitation-delivery
  "A pending delivery of the email for an invitation event, due now.
  `event` carries `:bank-id` and `:invitation-id`."
  [event changelog-event-id now]
  (let [{:keys [bank-id invitation-id]} event]
    {:bank-id bank-id
     :delivery-id (utility/generate-id "eml")
     :kind :email-kind-invitation
     :status pending
     :kind-id invitation-id
     :idempotency-key changelog-event-id
     :created-at now
     :updated-at now
     :attempt-count 0
     :next-attempt-at now}))

(defn supersession
  "Why an invitation's delivery is no longer wanted, or nil: a newer
  delivery about the invitation, or an invitation not pending as of now."
  [invitation newer?]
  (cond
   newer?
   "a newer email about the same invitation"

   (not= invitation-pending (:status invitation))
   (str "invitation is " (name (:status invitation)))))

(defn- settled
  [delivery now]
  (-> delivery
      (assoc :updated-at now)
      (dissoc :next-attempt-at)))

(defn mark-sent
  "The delivery once the mail server accepted the message, with the
  Message-ID it was handed."
  [delivery message-id now]
  (-> (settled delivery now)
      (assoc :status sent
             :sent-at now
             :attempt-count (inc (:attempt-count delivery)))
      (utility/assoc-some :message-id message-id)))

(defn mark-superseded
  "The delivery once nothing is to be sent for it."
  [delivery now]
  (assoc (settled delivery now) :status superseded))

(defn record-failure
  "The delivery as a failed attempt leaves it: the attempt counted and
  the next due after `retry-policy`'s backoff, or failed with `error` as
  its reason once it has made its `:max-attempts` or is older than its
  `:max-age-ms`. The claim is released either way."
  [delivery retry-policy error now]
  (let [attempt-count (inc (:attempt-count delivery))
        age-ms (- now (:created-at delivery))
        base (assoc (settled delivery now) :attempt-count attempt-count)]
    (if (circuit-breaker/give-up? retry-policy attempt-count age-ms)
      (assoc base :status failed :failure-reason error)
      (assoc base
             :status pending
             :next-attempt-at
             (+ now (circuit-breaker/backoff-ms retry-policy attempt-count))))))
