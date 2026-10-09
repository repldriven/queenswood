(ns com.repldriven.queenswood.api.access.handlers
  "Who may act for a bank: the signed-in person's members and
  invitations, the bank's members and invitations, and its audit
  log. A write is a command to the
  `member` processor, whose reply names the records it wrote, read
  back through `member-query`.

  See [ADR-0018](../../../../../../../docs/adr/0018-command-writes-are-earned.md)."
  (:require
    [com.repldriven.queenswood.api.access.names :as names]

    [com.repldriven.queenswood.api.commands :as commands]
    [com.repldriven.queenswood.api.cursor :as cursor]
    [com.repldriven.queenswood.api.errors :as errors]
    [com.repldriven.queenswood.api.shared.actor :as shared.actor]

    [com.repldriven.queenswood.bank-query.interface :as banks]
    [com.repldriven.queenswood.member-query.interface :as members]
    [com.repldriven.queenswood.user.interface :as users]

    [com.repldriven.mono.error.interface :as error :refer [let-nom>]]
    [com.repldriven.mono.utility.interface :as utility]))

(def ^:private invitations-path "/v1/invitations")

(def ^:private members-path "/v1/members")

(def ^:private my-invitations-path "/v1/me/invitations")

(def ^:private my-members-path "/v1/me/members")

(def ^:private audit-events-path "/v1/bank/audit-events")

(defn- invitation-uri
  [{:keys [invitation-id]}]
  (str "/v1/invitations/" invitation-id))

(defn- my-member-uri
  [{:keys [member-id]}]
  (str "/v1/me/members/" member-id))

(defn- config
  [{:keys [record-db record-store]}]
  {:record-db record-db :record-store record-store})

(defn- respond
  [result success]
  (if (error/anomaly? result)
    (errors/anomaly->response result)
    (success result)))

(defn- send-command
  "Send `command` to the `member` processor and, when it is accepted,
  answer `(success change)` with the ids its reply names; otherwise the
  refusal's response."
  [request command data success]
  (let [{:keys [dispatchers]} request
        result (commands/send (:members dispatchers)
                              request
                              command
                              "member-command-reply"
                              data)]
    (if (= 200 (:status result)) (success (:body result)) result)))

(defn- actor
  "The principal as an audit event records it: the caller as an actor,
  a member with the role of the member the call resolved to."
  [auth]
  (let [actor (shared.actor/actor auth)]
    (cond-> actor

            (= :actor-kind-member (:kind actor))
            (assoc :role (:role (:member auth))))))

(defn- proof
  "The recipient's proof: the `Invitation-Token` header's hash, and the
  token's email with whether its `email_verified` claim is true."
  [request]
  (let [{:keys [auth headers]} request
        {:keys [claims]} auth]
    {:token-hash (members/token-hash (get headers "invitation-token"))
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
  [member user bank-name invitation names]
  (let [{:keys [invitation-id]} member]
    (-> (select-keys member
                     [:member-id :bank-id :user-id :role :created-at
                      :updated-at])
        (assoc :created-organisation (nil? invitation-id))
        (utility/assoc-some :bank-name bank-name
                            :name (:name user)
                            :email (:email user)
                            :invitation-id invitation-id
                            :invited-by (some-> (:created-by invitation)
                                                (names/->actor names))
                            :invited-email (:email invitation)))))

(defn founding-member
  "The owner member a bank's creation writes, as `Member` shows
  it, from the `user` and the `bank` already in hand: it joined by no
  invitation, so there is nothing more to read."
  [member user bank]
  (->member member user (:name bank) nil {}))

(defn- member-records
  [txn member]
  (let [{:keys [bank-id user-id invitation-id]} member]
    (let-nom>
      [user (find-user txn user-id)
       invitation (when invitation-id
                    (found-or-nil (members/find-invitation txn
                                                           bank-id
                                                           invitation-id)
                                  :invitation/not-found))]
      {:member member :user user :invitation invitation})))

(defn named-members
  "Each member as `Member` shows it: its person, its bank's name,
  and the invitation it was accepted from with its inviter named. An
  anomaly when a user or invitation record cannot be read."
  [txn found]
  (let-nom> [records (all-or-anomaly (fn [member]
                                       (member-records txn member))
                                     found)
             names (names/user-names (lookup txn)
                                     (names/actor-ids
                                      (map (comp :created-by :invitation)
                                           records)))]
    (let [bank-names (into {}
                           (map (fn [bank-id] [bank-id
                                               (bank-name txn bank-id)]))
                           (distinct (map :bank-id found)))]
      (mapv (fn [{:keys [member user invitation]}]
              (->member member
                        user
                        (get bank-names (:bank-id member))
                        invitation
                        names))
            records))))

(defn- named-member
  [txn member]
  (let-nom> [found (named-members txn [member])]
    (first found)))

(defn- ->invitation
  [invitation names accepted-email]
  (-> (select-keys invitation
                   [:invitation-id :bank-id :email :role :status :expires-at
                    :reason :created-at :updated-at])
      (assoc :invited-by (names/->actor (:created-by invitation) names))
      (utility/assoc-some :accepted-by-user-id
                          (get-in invitation [:accepted-by :principal-id])
                          :accepted-email accepted-email)))

(defn- inviter-names
  [txn invitations]
  (names/user-names (lookup txn)
                    (names/actor-ids (map :created-by invitations))))

(defn- invitations
  [txn found]
  (let-nom> [accepted (all-or-anomaly (fn [invitation]
                                        (find-user txn
                                                   (get-in invitation
                                                           [:accepted-by
                                                            :principal-id])))
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
             (names/->actor (:created-by invitation)
                            names
                            (or bank-name names/platform-name)))
      (utility/assoc-some :bank-name bank-name)))

(defn- recipient-invitations
  [txn found]
  (let-nom> [names (names/recipient-names (lookup txn)
                                          (names/actor-ids (map :created-by
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
                          (members/list-pending-invitations-by-email
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
    (respond (let-nom> [found (members/find-invitation-for-recipient
                               txn
                               invitation-id
                               (proof request))]
               (recipient-invitation txn found))
             ok)))

(defn accept-my-invitation
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
     (fn [{:keys [bank-id member-id]}]
       (respond (let-nom> [member (members/find-by-id txn
                                                      bank-id
                                                      member-id)]
                  (named-member txn member))
                (created my-member-uri))))))

(defn decline-my-invitation
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
       (respond (let-nom> [declined (members/find-invitation txn
                                                             bank-id
                                                             invitation-id)]
                  (recipient-invitation txn declined))
                ok)))))

(defn- member-not-found
  [member-id]
  (error/reject :member/not-found
                {:message "Member not found" :member-id member-id}))

(defn- active-for
  "The member when it is active and `k` of it is `v`, otherwise the
  not-found rejection, so a member elsewhere reads as none at all."
  [member k v]
  (let [{:keys [member-id status]} member]
    (if (and (= v (get member k)) (= :member-status-active status))
      member
      (member-not-found member-id))))

(defn- page-members
  [txn path page active]
  (let-nom> [windowed (cursor/window (sort-by :member-id active)
                                     :member-id
                                     :asc
                                     page)
             listed (named-members txn (:page windowed))]
    (cursor/page-body path page listed windowed)))

(defn list-my-members
  [request]
  (let [{:keys [auth parameters]} request
        {:keys [page]} (:query parameters)]
    (respond (page-members (config request)
                           my-members-path
                           page
                           (or (:members auth) []))
             ok)))

(defn get-my-member
  [request]
  (let [{:keys [auth parameters]} request
        {:keys [member-id]} (:path parameters)
        txn (config request)]
    (respond (let-nom> [found (members/find-user-member
                               txn
                               (:principal-id auth)
                               member-id)
                        active (active-for found :user-id (:principal-id auth))]
               (named-member txn active))
             ok)))

(defn leave-my-member
  [request]
  (let [{:keys [auth parameters]} request
        {:keys [member-id]} (:path parameters)]
    (send-command request
                  "leave-bank"
                  {:member-id member-id
                   :user-id (:principal-id auth)}
                  no-content)))

(defn list-members
  [request]
  (let [{:keys [auth parameters]} request
        {:keys [bank-id]} auth
        {:keys [page]} (:query parameters)
        txn (config request)]
    (respond (let-nom> [active (members/list-active-by-bank txn bank-id)]
               (page-members txn members-path page active))
             ok)))

(defn get-member
  [request]
  (let [{:keys [auth parameters]} request
        {:keys [bank-id]} auth
        {:keys [member-id]} (:path parameters)
        txn (config request)]
    (respond (let-nom> [found (members/find-by-id txn
                                                  bank-id
                                                  member-id)
                        active (active-for found :bank-id bank-id)]
               (named-member txn active))
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
     "change-member-role"
     {:bank-id bank-id
      :member-id (:member-id path)
      :role role
      :actor (actor auth)
      :reason reason}
     (fn [{:keys [member-id]}]
       (respond (let-nom> [changed (members/find-by-id txn
                                                       bank-id
                                                       member-id)]
                  (named-member txn changed))
                ok)))))

(defn remove-member
  [request]
  (let [{:keys [auth parameters]} request
        {:keys [bank-id]} auth
        {:keys [path body]} parameters]
    (send-command request
                  "remove-member"
                  {:bank-id bank-id
                   :member-id (:member-id path)
                   :actor (actor auth)
                   :reason (:reason body)}
                  no-content)))

(defn list-invitations
  [request]
  (let [{:keys [auth parameters]} request
        {:keys [bank-id]} auth
        {:keys [page]} (:query parameters)
        txn (config request)]
    (respond (let-nom> [found (members/page-invitations-by-bank
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
    (respond (let-nom> [found (members/find-invitation txn
                                                       bank-id
                                                       invitation-id)
                        named (invitations txn [found])]
               (first named))
             ok)))

(defn named-invitation
  "The invitation of `bank-id` as the bank's members see it, its inviter
  named. An anomaly when it or the inviter's user record cannot be read."
  [txn bank-id invitation-id]
  (let-nom> [invitation (members/find-invitation txn bank-id invitation-id)
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
                  "invite-member"
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
        {:keys [path]} parameters]
    (send-command request
                  "resend-invitation"
                  {:bank-id bank-id
                   :invitation-id (:invitation-id path)
                   :actor (actor auth)}
                  (invitation-change request ok))))

(defn- ->audit-event
  "An audit event as the bank's audit log shows it, its actor and
  subject named."
  [audit-event names]
  (-> audit-event
      (update :actor names/->actor names)
      (utility/assoc-some :subject-name
                          (get names (:subject-user-id audit-event)))))

(defn- named-audit-events
  [txn events]
  (let-nom> [names (names/user-names
                    (lookup txn)
                    (concat (names/actor-ids (map :actor events))
                            (keep :subject-user-id events)))]
    (mapv (fn [event] (->audit-event event names)) events)))

(defn list-audit-events
  [request]
  (let [{:keys [auth parameters]} request
        {:keys [bank-id]} auth
        {:keys [page]} (:query parameters)
        txn (config request)]
    (respond (let-nom> [bank (banks/get-bank txn bank-id)
                        events (members/list-audit-events txn bank)
                        windowed (cursor/window events
                                                :audit-event-id
                                                :desc
                                                page)
                        named (named-audit-events txn (:page windowed))]
               (cursor/page-body audit-events-path page named windowed))
             ok)))
