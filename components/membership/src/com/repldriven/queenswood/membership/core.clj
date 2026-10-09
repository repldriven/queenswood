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

(defn new-membership
  [txn {:keys [user-id bank-id role actor]}]
  (store/transact
   txn
   (fn [txn]
     (let [membership (domain/new-membership
                       {:user-id user-id
                        :bank-id bank-id
                        :role role
                        :actor actor}
                       (utility/now))]
       (let-nom> [_ (store/save-membership txn membership)]
         membership)))
   :membership/new
   "Failed to create membership"))

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
          _ (store/save-invitation txn invitation invitation-created)]
         (as-read invitation now))))
   :invitation/invite
   "Failed to create invitation"))

(defn accept
  [txn invitation-id proof {:keys [user-id] :as opts}]
  (store/transact
   txn
   (fn [txn]
     (let [now (clock opts)
           actor (member-actor user-id)]
       (let-nom>
         [invitation (q/get-invitation-record-for-recipient txn
                                                            invitation-id
                                                            proof)
          members (q/list-active-by-bank txn (:bank-id invitation))
          accepted (domain/accept-invitation invitation
                                             actor
                                             {:active-memberships members}
                                             now)
          membership (domain/new-membership {:user-id user-id
                                             :bank-id (:bank-id accepted)
                                             :role (:role accepted)
                                             :invitation-id invitation-id
                                             :actor actor}
                                            now)
          _ (store/save-invitation txn accepted)
          _ (store/save-membership txn membership)]
         membership)))
   :invitation/accept
   "Failed to accept invitation"))

(defn decline
  [txn invitation-id proof {:keys [user-id] :as opts}]
  (store/transact
   txn
   (fn [txn]
     (let [now (clock opts)]
       (let-nom>
         [invitation (q/get-invitation-record-for-recipient txn
                                                            invitation-id
                                                            proof)
          declined (domain/decline-invitation invitation
                                              (member-actor user-id)
                                              now)
          _ (store/save-invitation txn declined)]
         (as-read declined now))))
   :invitation/decline
   "Failed to decline invitation"))

(defn withdraw
  [txn bank-id invitation-id {:keys [actor] :as opts}]
  (store/transact
   txn
   (fn [txn]
     (let [now (clock opts)]
       (let-nom>
         [invitation (q/get-invitation-record txn bank-id invitation-id)
          withdrawn (domain/withdraw-invitation invitation opts now)
          _ (domain/check-grant :invite actor {:role (:role invitation)})
          _ (store/save-invitation txn withdrawn)]
         (as-read withdrawn now))))
   :invitation/withdraw
   "Failed to withdraw invitation"))

(defn resend
  [txn bank-id invitation-id {:keys [actor] :as opts}]
  (store/transact
   txn
   (fn [txn]
     (let [now (clock opts)]
       (let-nom>
         [invitation (q/get-invitation-record txn bank-id invitation-id)
          resent (domain/resend-invitation invitation actor now)
          _ (domain/check-grant :invite actor {:role (:role invitation)})
          _ (store/save-invitation txn resent invitation-resent)]
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

(defn change-role
  [txn bank-id membership-id role {:keys [actor] :as opts}]
  (store/transact
   txn
   (fn [txn]
     (let [now (clock opts)]
       (let-nom>
         [membership (q/find-by-id txn bank-id membership-id)
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
              (store/save-role-change
               txn
               (domain/new-role-change membership role opts now)))]
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
         [membership (q/find-by-id txn bank-id membership-id)
          members (q/list-active-by-bank txn bank-id)
          ended (domain/end-membership membership
                                       :remove
                                       {:actor actor
                                        :active-memberships members
                                        :reason reason}
                                       now)
          _ (store/save-membership txn ended)]
         ended)))
   :membership/remove
   "Failed to remove member"))

(defn leave
  [txn membership-id {:keys [user-id] :as opts}]
  (store/transact
   txn
   (fn [txn]
     (let [now (clock opts)
           actor (member-actor user-id)]
       (let-nom>
         [membership (q/find-user-membership txn user-id membership-id)
          members (q/list-active-by-bank txn (:bank-id membership))
          ended (domain/end-membership membership
                                       :leave
                                       {:actor actor
                                        :active-memberships members}
                                       now)
          _ (store/save-membership txn ended)]
         ended)))
   :membership/leave
   "Failed to leave"))
