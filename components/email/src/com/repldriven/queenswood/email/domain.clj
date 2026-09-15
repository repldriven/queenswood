(ns com.repldriven.queenswood.email.domain
  (:require
    [com.repldriven.mono.utility.interface :as utility]))

(def ^:private pending :email-delivery-status-pending)
(def ^:private sent :email-delivery-status-sent)
(def ^:private superseded :email-delivery-status-superseded)
(def ^:private failed :email-delivery-status-failed)

(def ^:private invitation-pending :invitation-status-pending)

(def
  ^{:doc
    "The delay before the first retry, so a mail server that was
  restarting is tried again within the minute."}
  retry-base-ms
  30000)

(def ^{:doc "How much each retry delay grows on the one before it."}
     retry-growth
  4)

(def ^{:doc "The longest a retry delay grows to."} retry-max-interval-ms
  14400000)

(def
  ^{:doc
    "How long the schedule may span before a delivery is given up on,
  roughly a day."}
  retry-span-ms
  86400000)

(def
  ^{:doc
    "The delay before each retry, in order. Geometric from
  `retry-base-ms` by `retry-growth` until it would pass
  `retry-max-interval-ms`, then that interval, for as many retries as
  fit inside `retry-span-ms`. A delivery makes one more attempt than
  this has entries."}
  retry-schedule-ms
  (loop [delays []
         delay retry-base-ms
         span 0]
    (let [delay (min delay retry-max-interval-ms)
          span' (+ span delay)]
      (if (> span' retry-span-ms)
        delays
        (recur (conj delays delay) (* delay retry-growth) span')))))

(def
  ^{:doc
    "How many attempts a delivery makes before it is failed and kept:
  the first, and one per entry in the schedule."}
  max-attempts
  (inc (count retry-schedule-ms)))

(def
  ^{:doc
    "How long a runner's claim on a delivery holds. It outlasts the
  command's reply timeout and the mail server's connection and read
  timeouts together, and a claim whose lease has passed is taken again
  by the next pass."}
  claim-lease-ms
  60000)

(defn retry-schedule
  "The delay before the attempt after `attempts`, or nil when the
  schedule is spent."
  [attempts]
  (get retry-schedule-ms (dec (max 1 (or attempts 0)))))

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
  the next taken from the schedule, or failed once the schedule is
  spent. The claim is released either way."
  [delivery error now]
  (let [attempts (inc (or (:attempts delivery) 0))
        delay (retry-schedule attempts)
        base (-> (settled delivery now)
                 (assoc :attempts attempts)
                 (utility/assoc-some :last-error error))]
    (if (nil? delay)
      (assoc base :status failed)
      (assoc base :status pending :next-attempt-at (+ now delay)))))
