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
  `event` carries `:bank-id`, `:invitation-id` and `:expires-at`."
  [event changelog-event-id now]
  (let [{:keys [bank-id invitation-id expires-at]} event]
    {:bank-id bank-id
     :delivery-id (utility/generate-id "eml")
     :kind :email-kind-invitation
     :invitation-id invitation-id
     :expires-at expires-at
     :changelog-event-id changelog-event-id
     :status pending
     :next-attempt-at now
     :created-at now
     :updated-at now}))

(defn supersession
  "Why the invitation a delivery is for no longer wants this email, or
  nil. An invitation that is not pending as of now, or whose
  `expires-at` differs from the delivery's because it was sent again,
  wants none."
  [delivery invitation]
  (cond
   (not= invitation-pending (:status invitation))
   (str "invitation is " (name (:status invitation)))

   (not= (:expires-at delivery) (:expires-at invitation))
   "invitation was sent again"))

(defn- settled
  [delivery now]
  (-> delivery
      (assoc :updated-at now)
      (dissoc :claim-lease-expires-at :claimed-by :next-attempt-at)))

(defn mark-sent
  "The delivery once the mail server accepted the message, with the
  Message-ID it was handed."
  [delivery message-id now]
  (-> (settled delivery now)
      (assoc :status sent :attempts (inc (or (:attempts delivery) 0)))
      (utility/assoc-some :message-id message-id)))

(defn mark-superseded
  "The delivery once nothing is to be sent for it, with the reason."
  [delivery reason now]
  (-> (settled delivery now)
      (assoc :status superseded)
      (utility/assoc-some :last-error reason)))

(defn record-failure
  "The delivery as a failed attempt leaves it: the attempt counted and
  the next due after `retry-policy`'s backoff, or failed once it has
  made its `:max-attempts` or is older than its `:max-age-ms`. The claim
  is released either way."
  [delivery retry-policy error now]
  (let [attempts (inc (or (:attempts delivery) 0))
        age-ms (some->> (:created-at delivery)
                        (- now))
        base (-> (settled delivery now)
                 (assoc :attempts attempts)
                 (utility/assoc-some :last-error error))]
    (if (circuit-breaker/give-up? retry-policy attempts age-ms)
      (assoc base :status failed)
      (assoc base
             :status pending
             :next-attempt-at
             (+ now (circuit-breaker/backoff-ms retry-policy attempts))))))
