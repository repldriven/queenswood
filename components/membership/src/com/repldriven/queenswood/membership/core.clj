(ns com.repldriven.queenswood.membership.core
  (:require
    [com.repldriven.queenswood.membership.domain :as domain]
    [com.repldriven.queenswood.membership.store :as store]

    [com.repldriven.queenswood.user.interface :as user]

    [com.repldriven.mono.error.interface :as error :refer [let-nom>]]
    [com.repldriven.mono.utility.interface :as utility]

    [clojure.string :as str])
  (:import
    (java.security MessageDigest SecureRandom)
    (java.util Base64)))

(def ^:private token-bytes 32)

(def ^:private ^SecureRandom random (SecureRandom.))

(defn token-hash
  [token]
  (when (string? token)
    (let [digest (.digest (MessageDigest/getInstance "SHA-256")
                          (.getBytes ^String token "UTF-8"))]
      ;; A Java byte is signed, so mask before formatting or every byte
      ;; over 127 renders as eight f-padded characters.
      (apply str (map #(format "%02x" (bit-and % 0xff)) digest)))))

(defn new-invitation-token
  []
  (let [bytes (byte-array token-bytes)]
    (.nextBytes random bytes)
    (let [token (.encodeToString (.withoutPadding (Base64/getUrlEncoder))
                                 bytes)]
      {:token token :token-hash (token-hash token)})))

(defn- clock
  [opts]
  (or (:now opts) (utility/now)))

(defn- member-actor
  [user-id]
  {:kind :actor-kind-member :principal-id user-id})

(defn- as-read
  [invitation now]
  (-> invitation
      (assoc :status (domain/effective-status invitation now))
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

(defn- load-invitation
  [txn bank-id invitation-id]
  (let-nom> [invitation (store/find-invitation txn bank-id invitation-id)]
    (domain/ensure-invitation-found invitation invitation-id)))

(defn- load-for-recipient
  [txn invitation-id {:keys [token-hash email email-verified?] :as proof}]
  (let-nom>
    [by-token (when (string? token-hash)
                (store/find-invitation-by-token-hash txn token-hash))
     by-email (if (and (true? email-verified?) (string? email))
                (store/list-invitations-by-email txn (str/lower-case email))
                [])
     invitation (domain/ensure-invitation-found
                 (some #(when (= invitation-id (:invitation-id %)) %)
                       (cons by-token by-email))
                 invitation-id)
     _ (domain/check-recipient invitation proof)]
    invitation))

(defn- load-membership
  [txn membership-id]
  (let-nom> [membership (store/get-membership txn membership-id)]
    (domain/ensure-found membership membership-id)))

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

(defn list-by-user
  [txn user-id]
  (store/list-by-user txn user-id))

(defn list-by-bank
  [txn bank-id]
  (store/list-by-bank txn bank-id))

(defn list-active-by-user
  [txn user-id]
  (store/list-active-by-user txn user-id))

(defn list-active-by-bank
  [txn bank-id]
  (store/list-active-by-bank txn bank-id))

(defn find-by-id
  [txn membership-id]
  (load-membership txn membership-id))

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
  [txn bank-id {:keys [email role]} {:keys [actor token-hash reason] :as opts}]
  (store/transact
   txn
   (fn [txn]
     (let [now (clock opts)]
       (let-nom>
         [members (store/list-active-by-bank txn bank-id)
          emails (member-emails txn members)
          invitations (store/list-invitations-by-bank txn bank-id)
          invitation (domain/new-invitation {:bank-id bank-id
                                             :email email
                                             :role role
                                             :reason reason}
                                            {:actor actor
                                             :member-emails emails
                                             :invitations invitations}
                                            token-hash
                                            now)
          _ (store/save-invitation txn invitation)
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
         [invitation (load-for-recipient txn invitation-id proof)
          members (store/list-active-by-bank txn (:bank-id invitation))
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
         [invitation (load-for-recipient txn invitation-id proof)
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
         [invitation (load-invitation txn bank-id invitation-id)
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
  [txn bank-id invitation-id {:keys [actor token-hash reason] :as opts}]
  (store/transact
   txn
   (fn [txn]
     (let [now (clock opts)]
       (let-nom>
         [invitation (load-invitation txn bank-id invitation-id)
          resent (domain/resend-invitation invitation token-hash now)
          _ (domain/check-grant :invite actor {:role (:role invitation)})
          _ (store/save-invitation txn resent)
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
         [membership (load-membership txn membership-id)
          _ (domain/check-in-bank membership bank-id)
          members (store/list-active-by-bank txn bank-id)
          changed (domain/change-role membership
                                      role
                                      {:actor actor
                                       :active-memberships members}
                                      now)
          _ (store/save-membership txn changed)
          _ (store/save-access-event
             txn
             (membership-event membership
                               :access-event-kind-role-changed
                               actor
                               {:role-after role :reason reason}
                               now))]
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
         [membership (load-membership txn membership-id)
          _ (domain/check-in-bank membership bank-id)
          members (store/list-active-by-bank txn bank-id)
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
         [membership (load-membership txn membership-id)
          _ (domain/check-own membership user-id)
          members (store/list-active-by-bank txn (:bank-id membership))
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

(defn find-invitation
  [txn bank-id invitation-id opts]
  (let [now (clock opts)]
    (let-nom> [invitation (load-invitation txn bank-id invitation-id)]
      (as-read invitation now))))

(defn find-invitation-for-recipient
  [txn invitation-id proof opts]
  (store/transact
   txn
   (fn [txn]
     (let [now (clock opts)]
       (let-nom> [invitation (load-for-recipient txn invitation-id proof)]
         (as-read invitation now))))
   :invitation/find-for-recipient
   "Failed to load invitation"))

(defn list-invitations-by-bank
  [txn bank-id opts]
  (let [now (clock opts)]
    (let-nom> [invitations (store/list-invitations-by-bank txn bank-id)]
      (mapv #(as-read % now) invitations))))

(defn list-pending-invitations-by-email
  [txn email opts]
  (if-not (string? email)
    []
    (let [now (clock opts)]
      (let-nom> [invitations (store/list-invitations-by-email
                              txn
                              (str/lower-case email))]
        (into []
              (comp (map #(as-read % now))
                    (filter #(= :invitation-status-pending (:status %))))
              invitations)))))

(defn list-access-events
  [txn bank-id opts]
  (store/scan-access-events txn bank-id opts))
