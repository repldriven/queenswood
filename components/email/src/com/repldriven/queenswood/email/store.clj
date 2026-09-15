(ns com.repldriven.queenswood.email.store
  (:require
    [com.repldriven.queenswood.fdb.interface :as fdb]
    [com.repldriven.queenswood.schema.interface :as schema]

    [com.repldriven.mono.error.interface :as error]))

(def ^:private deliveries-store-name "email-deliveries")

(def ^:private pending :email-delivery-status-pending)
(def ^:private in-flight :email-delivery-status-in-flight)

(def transact fdb/transact)

(def uniqueness-violation? fdb/uniqueness-violation?)

(defn save-delivery
  [txn delivery]
  (fdb/transact
   txn
   (fn [txn]
     (fdb/save-record (fdb/open txn deliveries-store-name)
                      (schema/EmailDelivery->java delivery)))
   :email-delivery/save
   "Failed to save email delivery"))

(defn find-delivery
  [txn bank-id delivery-id]
  (fdb/transact
   txn
   (fn [txn]
     (some-> (fdb/load-record (fdb/open txn deliveries-store-name)
                              bank-id
                              delivery-id)
             schema/pb->EmailDelivery))
   :email-delivery/find
   "Failed to load email delivery"))

(defn find-delivery-by-changelog-event-id
  [txn changelog-event-id]
  (fdb/transact
   txn
   (fn [txn]
     (some-> (fdb/query-record (fdb/open txn deliveries-store-name)
                               "EmailDelivery"
                               "changelog_event_id"
                               changelog-event-id
                               {:index "EmailDelivery_by_changelog_event_id"})
             schema/pb->EmailDelivery))
   :email-delivery/find-by-changelog-event-id
   "Failed to find email delivery by changelog event id"))

(defn- deliveries-with-status
  [store status]
  (fdb/query-records store
                     "EmailDelivery"
                     "status"
                     (fdb/enum-value store
                                     "EmailDelivery"
                                     "status"
                                     (schema/email-delivery-status->int status))
                     {:index "EmailDelivery_by_status_due"}))

(defn- claimable?
  "Whether a row may be claimed now: a pending one whose next attempt
  has come, or an in-flight one whose lease has passed because the
  runner holding it stopped between the claim and the outcome."
  [{:keys [status next-attempt-at claim-lease-expires-at]} now]
  (if (= in-flight status)
    (<= (or claim-lease-expires-at 0) now)
    (<= (or next-attempt-at 0) now)))

(defn claim-due-deliveries
  "Claim the deliveries that are due in the transaction that read them:
  each takes a lease and the claiming runner and moves to in flight. A
  second runner reading the same rows loses the transaction and claims
  nothing. Rows whose lease has passed are scanned before pending ones,
  so a pending backlog cannot starve their recovery."
  [txn {:keys [now claimed-by lease-ms limit]}]
  (fdb/transact
   txn
   (fn [txn]
     (let [store (fdb/open txn deliveries-store-name)
           due (into []
                     (comp (map schema/pb->EmailDelivery)
                           (filter (fn [delivery] (claimable? delivery now)))
                           (take limit))
                     (concat (deliveries-with-status store in-flight)
                             (deliveries-with-status store pending)))]
       (reduce (fn [claimed delivery]
                 (let [row (assoc delivery
                                  :status in-flight
                                  :claim-lease-expires-at (+ now lease-ms)
                                  :claimed-by claimed-by
                                  :updated-at now)
                       res (fdb/save-record store
                                            (schema/EmailDelivery->java row))]
                   (if (error/anomaly? res) (reduced res) (conj claimed row))))
               []
               due)))
   :email-delivery/claim
   "Failed to claim due email deliveries"))
