(ns com.repldriven.queenswood.api.access.handlers
  "Who may act for a bank: the recipient's invitation routes, the bank's
  people routes and its access history. A write is a command to the
  `membership` processor, whose reply names the records it wrote, read
  back through `membership-query`.

  See [ADR-0018](../../../../../../../docs/adr/0018-command-writes-are-earned.md)."
  (:require
    [com.repldriven.queenswood.api.access.names :as names]

    [com.repldriven.queenswood.api.commands :as commands]
    [com.repldriven.queenswood.api.cursor :as cursor]
    [com.repldriven.queenswood.api.errors :as errors]

    [com.repldriven.queenswood.bank-query.interface :as banks]
    [com.repldriven.queenswood.membership-query.interface :as memberships]
    [com.repldriven.queenswood.user.interface :as users]

    [com.repldriven.mono.error.interface :as error :refer [let-nom>]]
    [com.repldriven.mono.utility.interface :as utility]))

(def ^:private access-events-path "/v1/access-events")

(def ^:private invitations-path "/v1/invitations")

(def ^:private members-path "/v1/members")

(def ^:private my-invitations-path "/v1/me/invitations")

(defn- invitation-uri
  [{:keys [invitation-id]}]
  (str "/v1/invitations/" invitation-id))

(defn- member-uri
  [{:keys [membership-id]}]
  (str "/v1/members/" membership-id))

(defn- config
  [{:keys [record-db record-store]}]
  {:record-db record-db :record-store record-store})

(defn- respond
  [result success]
  (if (error/anomaly? result)
    (errors/anomaly->response result)
    (success result)))

(defn- send-command
  "Send `command` to the `membership` processor and, when it is accepted,
  answer `(success change)` with the ids its reply names; otherwise the
  refusal's response."
  [request command data success]
  (let [{:keys [dispatchers]} request
        result (commands/send (:memberships dispatchers)
                              request
                              command
                              "access-change"
                              data)]
    (if (= 200 (:status result)) (success (:body result)) result)))

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

(defn- proof-data
  [proof]
  (let [{:keys [token-hash email email-verified?]} proof]
    {:token-hash token-hash :email email :email-verified email-verified?}))

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

(defn- created
  "A 201 answering `body`, its `Location` the URI `uri` makes of it."
  [uri]
  (fn [body] {:status 201 :headers {"Location" (uri body)} :body body}))

(defn- no-content [_] {:status 204})

(defn- items [results] (ok {:items results}))

(defn list-my-invitations
  [request]
  (let [{:keys [auth parameters]} request
        {:keys [claims]} auth
        {:keys [page]} (:query parameters)
        txn (config request)]
    (if-not (true? (:email_verified claims))
      (items [])
      (respond (let-nom> [invitations
                          (memberships/list-pending-invitations-by-email
                           txn
                           (:email claims))
                          windowed (cursor/window (sort-by :invitation-id
                                                           #(compare %2 %1)
                                                           invitations)
                                                  :invitation-id
                                                  :desc
                                                  page)
                          listed (recipient-invitations txn (:page windowed))]
                 (cursor/page-body my-invitations-path page listed windowed))
               ok))))

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
    (send-command
     request
     "accept-invitation"
     {:invitation-id invitation-id
      :user-id (:principal-id auth)
      :proof (proof-data (proof request))}
     (fn [{:keys [membership-id]}]
       (respond (let-nom> [membership (memberships/find-by-id txn
                                                              membership-id)]
                  (->membership membership
                                (bank-name txn (:bank-id membership))))
                (created member-uri))))))

(defn decline-invitation
  [request]
  (let [{:keys [auth parameters]} request
        {:keys [invitation-id]} (:path parameters)
        txn (config request)]
    (send-command
     request
     "decline-invitation"
     {:invitation-id invitation-id
      :user-id (:principal-id auth)
      :proof (proof-data (proof request))}
     (fn [{:keys [bank-id]}]
       (respond (let-nom> [declined (memberships/find-invitation txn
                                                                 bank-id
                                                                 invitation-id)]
                  (recipient-invitation txn declined))
                ok)))))

(defn leave
  [request]
  (let [{:keys [auth parameters]} request
        {:keys [membership-id]} (:path parameters)]
    (send-command request
                  "leave-membership"
                  {:membership-id membership-id
                   :user-id (:principal-id auth)}
                  no-content)))

(defn list-members
  [request]
  (let [{:keys [auth parameters]} request
        {:keys [bank-id]} auth
        {:keys [page]} (:query parameters)
        txn (config request)]
    (respond (let-nom> [active (memberships/list-active-by-bank txn bank-id)
                        windowed (cursor/window (sort-by :membership-id active)
                                                :membership-id
                                                :asc
                                                page)
                        listed (members txn (:page windowed))]
               (cursor/page-body members-path page listed windowed))
             ok)))

(defn- membership-not-found
  [membership-id]
  (error/reject :membership/not-found
                {:message "Membership not found" :membership-id membership-id}))

(defn- active-in-bank
  "The membership when it is active and of `bank-id`, otherwise the
  not-found rejection, so a membership elsewhere reads as none at all."
  [membership bank-id]
  (let [{:keys [membership-id status]} membership]
    (if (and (= bank-id (:bank-id membership))
             (= :membership-status-active status))
      membership
      (membership-not-found membership-id))))

(defn get-member
  [request]
  (let [{:keys [auth parameters]} request
        {:keys [bank-id]} auth
        {:keys [membership-id]} (:path parameters)
        txn (config request)]
    (respond (let-nom> [found (memberships/find-by-id txn membership-id)
                        active (active-in-bank found bank-id)]
               (member txn active))
             ok)))

(defn change-role
  [request]
  (let [{:keys [auth parameters]} request
        {:keys [bank-id]} auth
        {:keys [path body]} parameters
        {:keys [role reason]} body
        txn (config request)]
    (send-command
     request
     "change-role"
     {:bank-id bank-id
      :membership-id (:membership-id path)
      :role role
      :actor (actor auth)
      :reason reason}
     (fn [{:keys [membership-id]}]
       (respond (let-nom> [changed (memberships/find-by-id txn
                                                           membership-id)]
                  (member txn changed))
                ok)))))

(defn remove-member
  [request]
  (let [{:keys [auth parameters]} request
        {:keys [bank-id]} auth
        {:keys [path body]} parameters]
    (send-command request
                  "remove-member"
                  {:bank-id bank-id
                   :membership-id (:membership-id path)
                   :actor (actor auth)
                   :reason (:reason body)}
                  no-content)))

(defn list-invitations
  [request]
  (let [{:keys [auth parameters]} request
        {:keys [bank-id]} auth
        {:keys [page]} (:query parameters)
        txn (config request)]
    (respond (let-nom> [found (memberships/page-invitations-by-bank
                               txn
                               bank-id
                               (cursor/page-opts page))
                        listed (invitations txn (:invitations found))]
               (cursor/page-body invitations-path page listed found))
             ok)))

(defn get-invitation
  [request]
  (let [{:keys [auth parameters]} request
        {:keys [bank-id]} auth
        {:keys [invitation-id]} (:path parameters)
        txn (config request)]
    (respond (let-nom> [found (memberships/find-invitation txn
                                                           bank-id
                                                           invitation-id)
                        named (invitations txn [found])]
               (first named))
             ok)))

(defn named-invitation
  "The invitation of `bank-id` as the bank's members see it, its inviter
  named. An anomaly when it or the inviter's user record cannot be read."
  [txn bank-id invitation-id]
  (let-nom> [invitation (memberships/find-invitation txn bank-id invitation-id)
             names (inviter-names txn [invitation])]
    (->invitation invitation names nil)))

(defn- invitation-change
  [request success]
  (fn [{:keys [bank-id invitation-id]}]
    (respond (named-invitation (config request) bank-id invitation-id)
             success)))

(defn invite
  [request]
  (let [{:keys [auth parameters]} request
        {:keys [bank-id]} auth
        {:keys [email role reason]} (:body parameters)]
    (send-command request
                  "invite"
                  {:bank-id bank-id
                   :email email
                   :role role
                   :actor (actor auth)
                   :reason reason}
                  (invitation-change request (created invitation-uri)))))

(defn withdraw-invitation
  [request]
  (let [{:keys [auth parameters]} request
        {:keys [bank-id]} auth
        {:keys [path body]} parameters]
    (send-command request
                  "withdraw-invitation"
                  {:bank-id bank-id
                   :invitation-id (:invitation-id path)
                   :actor (actor auth)
                   :reason (:reason body)}
                  (invitation-change request ok))))

(defn resend-invitation
  [request]
  (let [{:keys [auth parameters]} request
        {:keys [bank-id]} auth
        {:keys [path body]} parameters]
    (send-command request
                  "resend-invitation"
                  {:bank-id bank-id
                   :invitation-id (:invitation-id path)
                   :actor (actor auth)
                   :reason (:reason body)}
                  (invitation-change request ok))))

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
        txn (config request)]
    (respond (let-nom> [found (memberships/list-access-events
                               txn
                               bank-id
                               (cursor/page-opts page))
                        named (named-access-events txn found)]
               (cursor/page-body access-events-path
                                 page
                                 (:access-events named)
                                 named))
             ok)))
