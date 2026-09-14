(ns com.repldriven.queenswood.membership.core
  (:require
    [com.repldriven.queenswood.membership.domain :as domain]
    [com.repldriven.queenswood.membership.store :as store]

    [com.repldriven.queenswood.membership-query.interface :as q]
    [com.repldriven.queenswood.user.interface :as user]

    [com.repldriven.mono.error.interface :as error :refer [let-nom>]]
    [com.repldriven.mono.utility.interface :as utility]))

(def ^:private invitation-created "invitation-created")
(def ^:private invitation-resent "invitation-resent")

(defn- clock
  [opts]
  (or (:now opts) (utility/now)))

(defn- member-actor
  [user-id]
  {:kind :actor-kind-member :principal-id user-id})

(defn- as-read
  [invitation now]
  (-> invitation
      (assoc :status (q/effective-status invitation now))
      (dissoc :token-hash)))

(defn- member-email
  [txn user-id]
  (let [result (user/find-by-id txn user-id)]
    (cond
     (= :user/not-found (error/kind result))
     nil

     (error/anomaly? result)
     result

     :else
     (:email result))))

(defn- member-emails
  [txn memberships]
  (reduce (fn [emails {:keys [user-id]}]
            (let [email (member-email txn user-id)]
              (cond
               (error/anomaly? email)
               (reduced email)
               (some? email)
               (conj emails email)
               :else
               emails)))
          []
          memberships))

(defn- invitation-event
  [invitation kind actor details now]
  (let [{:keys [bank-id invitation-id email role]} invitation]
    (domain/new-access-event bank-id
                             kind
                             actor
                             (merge {:invitation-id invitation-id
                                     :email email
                                     :role-after role}
                                    details)
                             now)))

(defn new-membership
  [txn {:keys [user-id bank-id role]}]
  (store/transact
   txn
   (fn [txn]
     (let [membership (domain/new-membership
                       {:user-id user-id
                        :bank-id bank-id
                        :role role}
                       (utility/now))]
       (let-nom> [_ (store/save-membership txn membership)]
         membership)))
   :membership/new
   "Failed to create membership"))

(defn record-bank-created
  [txn bank-id {:keys [actor membership reason] :as opts}]
  (store/transact
   txn
   (fn [txn]
     (let [now (clock opts)
           event (domain/new-access-event
                  bank-id
                  :access-event-kind-bank-created
                  actor
                  {:subject-user-id (:user-id membership)
                   :membership-id (:membership-id membership)
                   :role-after (:role membership)
                   :reason reason}
                  now)]
       (let-nom> [_ (store/save-access-event txn event)]
         event)))
   :access-event/bank-created
   "Failed to record bank creation"))

(defn invite
  [txn bank-id {:keys [email role]} {:keys [actor reason] :as opts}]
  (store/transact
   txn
   (fn [txn]
     (let [now (clock opts)]
       (let-nom>
         [members (q/list-active-by-bank txn bank-id)
          emails (member-emails txn members)
          invitations (q/list-invitations-by-bank txn bank-id {:now now})
          invitation (domain/new-invitation {:bank-id bank-id
                                             :email email
                                             :role role
                                             :reason reason}
                                            {:actor actor
                                             :member-emails emails
                                             :invitations invitations}
                                            now)
          _ (store/save-invitation txn invitation invitation-created)
          _ (store/save-access-event
             txn
             (invitation-event invitation
                               :access-event-kind-invitation-created
                               actor
                               {:reason reason}
                               now))]
         (as-read invitation now))))
   :invitation/invite
   "Failed to create invitation"))

(defn accept
  [txn invitation-id proof {:keys [user-id reason] :as opts}]
  (store/transact
   txn
   (fn [txn]
     (let [now (clock opts)]
       (let-nom>
         [invitation (q/get-invitation-record-for-recipient txn
                                                            invitation-id
                                                            proof)
          members (q/list-active-by-bank txn (:bank-id invitation))
          accepted (domain/accept-invitation invitation
                                             user-id
                                             {:active-memberships members}
                                             now)
          membership (domain/new-membership {:user-id user-id
                                             :bank-id (:bank-id accepted)
                                             :role (:role accepted)
                                             :invitation-id invitation-id}
                                            now)
          _ (store/save-invitation txn accepted)
          _ (store/save-membership txn membership)
          _ (store/save-access-event
             txn
             (invitation-event accepted
                               :access-event-kind-invitation-accepted
                               (member-actor user-id)
                               {:subject-user-id user-id
                                :membership-id (:membership-id membership)
                                :reason reason}
                               now))]
         membership)))
   :invitation/accept
   "Failed to accept invitation"))

(defn decline
  [txn invitation-id proof {:keys [user-id reason] :as opts}]
  (store/transact
   txn
   (fn [txn]
     (let [now (clock opts)]
       (let-nom>
         [invitation (q/get-invitation-record-for-recipient txn
                                                            invitation-id
                                                            proof)
          declined (domain/decline-invitation invitation now)
          _ (store/save-invitation txn declined)
          _ (store/save-access-event
             txn
             (invitation-event declined
                               :access-event-kind-invitation-declined
                               (member-actor user-id)
                               {:subject-user-id user-id :reason reason}
                               now))]
         (as-read declined now))))
   :invitation/decline
   "Failed to decline invitation"))

(defn withdraw
  [txn bank-id invitation-id {:keys [actor reason] :as opts}]
  (store/transact
   txn
   (fn [txn]
     (let [now (clock opts)]
       (let-nom>
         [invitation (q/get-invitation-record txn bank-id invitation-id)
          withdrawn (domain/withdraw-invitation invitation now)
          _ (domain/check-grant :invite actor {:role (:role invitation)})
          _ (store/save-invitation txn withdrawn)
          _ (store/save-access-event
             txn
             (invitation-event withdrawn
                               :access-event-kind-invitation-withdrawn
                               actor
                               {:reason reason}
                               now))]
         (as-read withdrawn now))))
   :invitation/withdraw
   "Failed to withdraw invitation"))

(defn resend
  [txn bank-id invitation-id {:keys [actor reason] :as opts}]
  (store/transact
   txn
   (fn [txn]
     (let [now (clock opts)]
       (let-nom>
         [invitation (q/get-invitation-record txn bank-id invitation-id)
          resent (domain/resend-invitation invitation now)
          _ (domain/check-grant :invite actor {:role (:role invitation)})
          _ (store/save-invitation txn resent invitation-resent)
          _ (store/save-access-event
             txn
             (invitation-event resent
                               :access-event-kind-invitation-resent
                               actor
                               {:reason reason}
                               now))]
         (as-read resent now))))
   :invitation/resend
   "Failed to resend invitation"))

(defn record-invitation-token
  [txn bank-id invitation-id {:keys [expires-at token-hash] :as opts}]
  (store/transact
   txn
   (fn [txn]
     (let [now (clock opts)]
       (let-nom>
         [invitation (q/get-invitation-record txn bank-id invitation-id)
          recorded (domain/record-invitation-token invitation
                                                   expires-at
                                                   token-hash
                                                   now)
          _ (store/save-invitation txn recorded)]
         (as-read recorded now))))
   :invitation/record-token
   "Failed to record invitation token"))

(defn- membership-event
  [membership kind actor details now]
  (let [{:keys [bank-id membership-id user-id role]} membership]
    (domain/new-access-event bank-id
                             kind
                             actor
                             (merge {:subject-user-id user-id
                                     :membership-id membership-id
                                     :role-before role}
                                    details)
                             now)))

(defn change-role
  [txn bank-id membership-id role {:keys [actor reason] :as opts}]
  (store/transact
   txn
   (fn [txn]
     (let [now (clock opts)]
       (let-nom>
         [membership (q/find-by-id txn membership-id)
          _ (domain/check-in-bank membership bank-id)
          members (q/list-active-by-bank txn bank-id)
          changed (domain/change-role membership
                                      role
                                      {:actor actor
                                       :active-memberships members}
                                      now)
          unchanged? (= role (:role membership))
          _ (when-not unchanged?
              (store/save-membership txn changed))
          _ (when-not unchanged?
              (store/save-access-event
               txn
               (membership-event membership
                                 :access-event-kind-role-changed
                                 actor
                                 {:role-after role :reason reason}
                                 now)))]
         changed)))
   :membership/change-role
   "Failed to change role"))

(defn remove-member
  [txn bank-id membership-id {:keys [actor reason] :as opts}]
  (store/transact
   txn
   (fn [txn]
     (let [now (clock opts)]
       (let-nom>
         [membership (q/find-by-id txn membership-id)
          _ (domain/check-in-bank membership bank-id)
          members (q/list-active-by-bank txn bank-id)
          ended (domain/end-membership membership
                                       :remove
                                       {:actor actor
                                        :active-memberships members}
                                       now)
          _ (store/save-membership txn ended)
          _ (store/save-access-event
             txn
             (membership-event membership
                               :access-event-kind-member-removed
                               actor
                               {:reason reason}
                               now))]
         ended)))
   :membership/remove
   "Failed to remove member"))

(defn leave
  [txn membership-id {:keys [user-id reason] :as opts}]
  (store/transact
   txn
   (fn [txn]
     (let [now (clock opts)
           actor (member-actor user-id)]
       (let-nom>
         [membership (q/find-by-id txn membership-id)
          _ (domain/check-own membership user-id)
          members (q/list-active-by-bank txn (:bank-id membership))
          ended (domain/end-membership membership
                                       :leave
                                       {:actor actor
                                        :active-memberships members}
                                       now)
          _ (store/save-membership txn ended)
          _ (store/save-access-event
             txn
             (membership-event membership
                               :access-event-kind-member-left
                               actor
                               {:reason reason}
                               now))]
         ended)))
   :membership/leave
   "Failed to leave"))
