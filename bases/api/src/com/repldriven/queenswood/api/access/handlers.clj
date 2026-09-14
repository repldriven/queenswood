(ns com.repldriven.queenswood.api.access.handlers
  "Who may act for a bank: the recipient's invitation routes, the bank's
  people routes and its access history, each a direct call into the
  `membership` component rather than a command.

  See [ADR-0018](../../../../../../../docs/adr/0018-command-writes-are-earned.md)."
  (:require
    [com.repldriven.queenswood.api.access.names :as names]

    [com.repldriven.queenswood.api.cursor :as cursor]
    [com.repldriven.queenswood.api.errors :as errors]

    [com.repldriven.queenswood.bank-query.interface :as banks]
    [com.repldriven.queenswood.membership.interface :as memberships]
    [com.repldriven.queenswood.user.interface :as users]

    [com.repldriven.mono.error.interface :as error :refer [let-nom>]]
    [com.repldriven.mono.utility.interface :as utility]))

(def ^:private access-events-path "/v1/access-events")

(defn- config
  [{:keys [record-db record-store]}]
  {:record-db record-db :record-store record-store})

(defn- respond
  [result success]
  (if (error/anomaly? result)
    (errors/anomaly->response result)
    (success result)))

(defn- actor
  "The principal as an access event records it: an operator when it
  carries `:admin`, otherwise a member with the role of the membership
  the call resolved to."
  [{:keys [roles principal-id membership]}]
  (if (contains? roles :admin)
    {:kind :actor-kind-operator :principal-id principal-id}
    {:kind :actor-kind-member
     :principal-id principal-id
     :role (:role membership)}))

(defn- proof
  "The recipient's proof: the `Invitation-Token` header's hash, and the
  token's email with whether its `email_verified` claim is true."
  [request]
  (let [{:keys [auth headers]} request
        {:keys [claims]} auth]
    {:token-hash (memberships/token-hash (get headers "invitation-token"))
     :email (:email claims)
     :email-verified? (true? (:email_verified claims))}))

(defn- found-or-nil
  [result not-found]
  (if (= not-found (error/kind result)) nil result))

(defn- find-user
  [txn user-id]
  (when user-id
    (found-or-nil (users/find-by-id txn user-id) :user/not-found)))

(defn- lookup
  [txn]
  (fn [user-id] (users/find-by-id txn user-id)))

(defn- bank-name
  [txn bank-id]
  (let [bank (banks/get-bank txn bank-id)]
    (when-not (error/anomaly? bank) (:name bank))))

(defn- all-or-anomaly
  [f items]
  (reduce
   (fn [results item]
     (let [result (f item)]
       (if (error/anomaly? result) (reduced result) (conj results result))))
   []
   items))

(defn- ->member
  [membership user invitation names]
  (let [{:keys [membership-id user-id role created-at invitation-id]}
        membership]
    (utility/assoc-some {:membership-id membership-id
                         :user-id user-id
                         :role role
                         :joined-at created-at
                         :created-organisation (nil? invitation-id)}
                        :name (:name user)
                        :email (:email user)
                        :invitation-id invitation-id
                        :invited-by (some-> (:invited-by invitation)
                                            (names/->actor names))
                        :invited-email (:email invitation))))

(defn- member-records
  [txn membership]
  (let [{:keys [bank-id user-id invitation-id]} membership]
    (let-nom>
      [user (find-user txn user-id)
       invitation (when invitation-id
                    (found-or-nil (memberships/find-invitation txn
                                                               bank-id
                                                               invitation-id)
                                  :invitation/not-found))]
      {:membership membership :user user :invitation invitation})))

(defn- members
  [txn memberships]
  (let-nom> [records (all-or-anomaly (fn [membership]
                                       (member-records txn membership))
                                     memberships)
             names (names/user-names (lookup txn)
                                     (names/actor-ids
                                      (map (comp :invited-by :invitation)
                                           records)))]
    (mapv (fn [{:keys [membership user invitation]}]
            (->member membership user invitation names))
          records)))

(defn- member
  [txn membership]
  (let-nom> [found (members txn [membership])]
    (first found)))

(defn- ->membership
  [membership bank-name]
  (utility/assoc-some (select-keys membership
                                   [:membership-id :user-id :bank-id :role
                                    :created-at :updated-at])
                      :bank-name
                      bank-name))

(defn- ->invitation
  [invitation names accepted-email]
  (-> (select-keys invitation
                   [:invitation-id :bank-id :email :role :status :expires-at
                    :reason :accepted-by-user-id :created-at :updated-at])
      (assoc :invited-by (names/->actor (:invited-by invitation) names))
      (utility/assoc-some :accepted-email accepted-email)))

(defn- inviter-names
  [txn invitations]
  (names/user-names (lookup txn)
                    (names/actor-ids (map :invited-by invitations))))

(defn- invitations
  [txn found]
  (let-nom> [accepted (all-or-anomaly (fn [invitation]
                                        (find-user txn
                                                   (:accepted-by-user-id
                                                    invitation)))
                                      found)
             names (inviter-names txn found)]
    (mapv (fn [invitation user] (->invitation invitation names (:email user)))
          found
          accepted)))

(defn- ->recipient-invitation
  [invitation bank-name names]
  (-> (select-keys invitation
                   [:invitation-id :bank-id :email :role :status :expires-at
                    :created-at])
      (assoc :invited-by
             (names/->actor (:invited-by invitation)
                            names
                            (or bank-name names/platform-name)))
      (utility/assoc-some :bank-name bank-name)))

(defn- recipient-invitations
  [txn found]
  (let-nom> [names (names/recipient-names (lookup txn)
                                          (names/actor-ids (map :invited-by
                                                                found)))]
    (mapv (fn [invitation]
            (->recipient-invitation invitation
                                    (bank-name txn (:bank-id invitation))
                                    names))
          found)))

(defn- recipient-invitation
  [txn invitation]
  (let-nom> [found (recipient-invitations txn [invitation])]
    (first found)))

(defn- ok [body] {:status 200 :body body})

(defn- created [body] {:status 201 :body body})

(defn- no-content [_] {:status 204})

(defn- items [results] (ok {:items results}))

(defn list-my-invitations
  [request]
  (let [{:keys [auth]} request
        {:keys [claims]} auth
        txn (config request)]
    (if-not (true? (:email_verified claims))
      (items [])
      (respond (let-nom> [invitations
                          (memberships/list-pending-invitations-by-email
                           txn
                           (:email claims))]
                 (recipient-invitations txn invitations))
               items))))

(defn get-my-invitation
  [request]
  (let [txn (config request)
        {:keys [invitation-id]} (get-in request [:parameters :path])]
    (respond (let-nom> [found (memberships/find-invitation-for-recipient
                               txn
                               invitation-id
                               (proof request))]
               (recipient-invitation txn found))
             ok)))

(defn accept-invitation
  [request]
  (let [{:keys [auth parameters]} request
        {:keys [invitation-id]} (:path parameters)
        txn (config request)]
    (respond (let-nom> [membership (memberships/accept txn
                                                       invitation-id
                                                       (proof request)
                                                       {:user-id
                                                        (:principal-id auth)})]
               (->membership membership (bank-name txn (:bank-id membership))))
             created)))

(defn decline-invitation
  [request]
  (let [{:keys [auth parameters]} request
        {:keys [invitation-id]} (:path parameters)
        txn (config request)]
    (respond (let-nom> [declined (memberships/decline txn
                                                      invitation-id
                                                      (proof request)
                                                      {:user-id
                                                       (:principal-id auth)})]
               (recipient-invitation txn declined))
             ok)))

(defn leave
  [request]
  (let [{:keys [auth parameters]} request
        {:keys [membership-id]} (:path parameters)]
    (respond (memberships/leave (config request)
                                membership-id
                                {:user-id (:principal-id auth)})
             no-content)))

(defn list-members
  [request]
  (let [{:keys [bank-id]} (:auth request)
        txn (config request)]
    (respond (let-nom> [active (memberships/list-active-by-bank txn bank-id)]
               (members txn active))
             items)))

(defn change-role
  [request]
  (let [{:keys [auth parameters]} request
        {:keys [bank-id]} auth
        {:keys [path body]} parameters
        {:keys [role reason]} body
        txn (config request)]
    (respond (let-nom> [changed (memberships/change-role
                                 txn
                                 bank-id
                                 (:membership-id path)
                                 role
                                 (utility/assoc-some {:actor (actor auth)}
                                                     :reason
                                                     reason))]
               (member txn changed))
             ok)))

(defn remove-member
  [request]
  (let [{:keys [auth parameters]} request
        {:keys [bank-id]} auth
        {:keys [path body]} parameters]
    (respond (memberships/remove-member (config request)
                                        bank-id
                                        (:membership-id path)
                                        (utility/assoc-some {:actor (actor
                                                                     auth)}
                                                            :reason
                                                            (:reason body)))
             no-content)))

(defn list-invitations
  [request]
  (let [{:keys [bank-id]} (:auth request)
        txn (config request)]
    (respond (let-nom> [found (memberships/list-invitations-by-bank txn
                                                                    bank-id)]
               (invitations txn found))
             items)))

(defn invitation-with-token
  "The invitation as the bank's members see it, its inviter named, beside
  the plaintext token whose hash it stores. An anomaly when the inviter's
  user record cannot be read."
  [txn invitation token]
  (let-nom> [names (inviter-names txn [invitation])]
    {:invitation (->invitation invitation names nil) :token token}))

(defn invite
  [request]
  (let [{:keys [auth parameters]} request
        {:keys [bank-id]} auth
        {:keys [email role reason]} (:body parameters)
        {:keys [token token-hash]} (memberships/new-invitation-token)
        txn (config request)]
    (respond (let-nom> [invited (memberships/invite
                                 txn
                                 bank-id
                                 {:email email :role role}
                                 (utility/assoc-some {:actor (actor auth)
                                                      :token-hash token-hash}
                                                     :reason
                                                     reason))]
               (invitation-with-token txn invited token))
             created)))

(defn withdraw-invitation
  [request]
  (let [{:keys [auth parameters]} request
        {:keys [bank-id]} auth
        {:keys [path body]} parameters
        txn (config request)]
    (respond (let-nom> [withdrawn (memberships/withdraw
                                   txn
                                   bank-id
                                   (:invitation-id path)
                                   (utility/assoc-some {:actor (actor auth)}
                                                       :reason
                                                       (:reason body)))
                        names (inviter-names txn [withdrawn])]
               (->invitation withdrawn names nil))
             ok)))

(defn resend-invitation
  [request]
  (let [{:keys [auth parameters]} request
        {:keys [bank-id]} auth
        {:keys [path body]} parameters
        {:keys [token token-hash]} (memberships/new-invitation-token)
        txn (config request)]
    (respond (let-nom> [resent (memberships/resend
                                txn
                                bank-id
                                (:invitation-id path)
                                (utility/assoc-some {:actor (actor auth)
                                                     :token-hash token-hash}
                                                    :reason
                                                    (:reason body)))]
               (invitation-with-token txn resent token))
             ok)))

(defn- ->access-event
  [access-event names]
  (-> access-event
      (update :actor names/->actor names)
      (utility/assoc-some :subject-name
                          (get names (:subject-user-id access-event)))))

(defn- named-access-events
  [txn found]
  (let [{:keys [access-events]} found]
    (let-nom> [names (names/user-names
                      (lookup txn)
                      (concat (names/actor-ids (map :actor access-events))
                              (keep :subject-user-id access-events)))]
      (assoc found
             :access-events
             (mapv (fn [access-event] (->access-event access-event names))
                   access-events)))))

(defn list-access-events
  [request]
  (let [{:keys [auth parameters]} request
        {:keys [bank-id]} auth
        {:keys [page]} (:query parameters)
        {:keys [after before size]} page
        after-id (cursor/decode after)
        before-id (cursor/decode before)
        size (cursor/clamp-size size)
        txn (config request)]
    (respond
     (let-nom> [found (memberships/list-access-events
                       txn
                       bank-id
                       (utility/assoc-some {:limit size}
                                           :after after-id
                                           :before before-id))]
       (named-access-events txn found))
     (fn [{:keys [access-events] next-cursor :after prev-cursor :before}]
       (ok (utility/assoc-seq
            {:items access-events}
            :links
            (when (seq access-events)
              (cursor/build-links access-events-path
                                  size
                                  (when after-id prev-cursor)
                                  next-cursor))))))))
