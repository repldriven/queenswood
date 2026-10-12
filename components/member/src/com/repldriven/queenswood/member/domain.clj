(ns com.repldriven.queenswood.member.domain
  (:require
    [com.repldriven.queenswood.member-query.interface :as q]
    [com.repldriven.queenswood.policy.interface :as policy]

    [com.repldriven.mono.error.interface :as error :refer [let-nom>]]
    [com.repldriven.mono.utility.interface :as utility]

    [clojure.string :as str]))

(def ^:private owner :role-owner)
(def ^:private admin :role-admin)
(def ^:private developer :role-developer)
(def ^:private viewer :role-viewer)

(def
  ^{:doc
    "Each role's level, lowest first. A role reaches everything a lower
  role does."}
  role-rank
  {viewer 1 developer 2 admin 3 owner 4})

(def ^:private member :actor-kind-member)
(def ^:private operator :actor-kind-operator)

(defn effective-role
  [actor]
  (if (= operator (:kind actor))
    owner
    (:role actor)))

(defn- actor-record
  [actor]
  (select-keys actor [:kind :principal-id]))

(defn- manages?
  [actor role]
  (let [rank (role-rank (effective-role actor))
        target (role-rank role)]
    (boolean (and rank target (<= (role-rank admin) rank) (<= target rank)))))

(defn may-invite?
  [actor role]
  (manages? actor role))

(defn may-change-role?
  [actor target-role new-role]
  (and (manages? actor target-role) (manages? actor new-role)))

(defn may-remove?
  [actor target-role]
  (manages? actor target-role))

(defn may-leave?
  [actor]
  (= member (:kind actor)))

(defn check-grant
  [action actor {:keys [role target-role new-role]}]
  (when-not (case action
              :invite (may-invite? actor role)
              :change-role (may-change-role? actor target-role new-role)
              :remove (may-remove? actor target-role)
              :leave (may-leave? actor)
              false)
    (error/unauthorized :member/role-not-granted
                        (utility/assoc-some
                         {:message "Your role does not allow this"
                          :action action
                          :actor-role (effective-role actor)}
                         :role role
                         :target-role target-role
                         :new-role new-role))))

(defn check-reason
  [actor reason]
  (when (and (= operator (:kind actor)) (str/blank? reason))
    (error/reject :invitation/reason-required
                  {:message "An operator's invitation needs a reason"})))

(def ^:private active :member-status-active)

(def ^:private ended
  {:remove {:status :member-status-removed :reason :removed-reason}
   :leave {:status :member-status-left}})

(defn- some-reason
  [reason]
  (when-not (str/blank? reason) reason))

(defn- active-owner?
  [member]
  (and (= owner (:role member)) (= active (:status member))))

(defn check-not-last-owner
  [active-members target new-role]
  (when (and (active-owner? target)
             (not= owner new-role)
             (not-any? #(and (active-owner? %)
                             (not= (:member-id target) (:member-id %)))
                       active-members))
    (error/reject :member/last-owner
                  {:message "Make someone else an owner first"
                   :member-id (:member-id target)})))

(defn check-not-member
  [active-members user-id]
  (when (some #(and (= user-id (:user-id %)) (= active (:status %)))
              active-members)
    (error/reject :member/already-exists
                  {:message "Already a member of this bank"
                   :user-id user-id})))

(defn check-not-member-email
  [member-emails email-lower]
  (when (some #(= email-lower
                  (some-> %
                          str/lower-case))
              member-emails)
    (error/reject :invitation/already-member
                  {:message "That address belongs to a member already"})))

(def
  ^{:doc
    "How long an invitation stays pending after it is created or resent,
  in milliseconds — seven days."}
  invitation-lifetime-ms
  (* 7 24 60 60 1000))

(def ^:private day-ms 86400000)

(defn day-of
  [now]
  (quot now day-ms))

(defn- check-limit
  [kind dimensions window aggregates policies]
  (policy/check-limit policies
                      kind
                      {:aggregate :count
                       :window window
                       :value (inc (get-in aggregates [kind dimensions]))}))

(defn- check-member-limit
  [aggregates policies]
  (check-limit :member
               #{:bank-id :status}
               :time-window-instant
               aggregates
               policies))

(defn- check-pending-limit
  [aggregates policies]
  (check-limit :invitation
               #{:bank-id :status}
               :time-window-instant
               aggregates
               policies))

(defn- check-sent-limit
  [aggregates policies]
  (check-limit :invitation
               #{:bank-id :sent-on}
               :time-window-daily
               aggregates
               policies))

(defn- check-email-limit
  [aggregates policies]
  (check-limit :email #{:sent-on} :time-window-daily aggregates policies))

(def ^:private pending :invitation-status-pending)
(def ^:private accepted :invitation-status-accepted)
(def ^:private declined :invitation-status-declined)
(def ^:private withdrawn :invitation-status-withdrawn)
(def ^:private expired :invitation-status-expired)

(defn check-no-pending
  [invitations email-lower now]
  (when-let [invitation (some #(when (and (= email-lower (:email-lower %))
                                          (= pending
                                             (q/effective-status % now)))
                                 %)
                              invitations)]
    (error/reject :invitation/already-exists
                  {:message "That address has a pending invitation"
                   :invitation-id (:invitation-id invitation)})))

(defn ensure-invitation-status
  [invitation allowed now]
  (let [status (q/effective-status invitation now)]
    (when-not (contains? allowed status)
      (error/reject :invitation/invalid-status
                    {:message "Invitation is not in a state that allows this"
                     :invitation-id (:invitation-id invitation)
                     :status status
                     :allowed allowed}))))

(defn ensure-member-status
  [member allowed]
  (when-not (contains? allowed (:status member))
    (error/reject :member/invalid-status
                  {:message "Member is not in a state that allows this"
                   :member-id (:member-id member)
                   :status (:status member)
                   :allowed allowed})))

(defn new-member
  [{:keys [user-id bank-id role invitation-id actor]} now]
  (utility/assoc-some {:member-id (utility/generate-id "mem")
                       :user-id user-id
                       :bank-id bank-id
                       :role (or role owner)
                       :status active
                       :created-at now
                       :created-by (actor-record actor)}
                      :invitation-id
                      invitation-id))

(defn change-role
  [member new-role {:keys [actor active-members reason]} now]
  (let-nom>
    [_ (ensure-member-status member #{active})
     _ (check-grant :change-role
                    actor
                    {:target-role (:role member) :new-role new-role})
     _ (check-not-last-owner active-members member new-role)]
    (if (= new-role (:role member))
      member
      (-> member
          (assoc :role new-role
                 :updated-by (actor-record actor)
                 :updated-at now)
          (cond->
           (some? reason)
           (assoc :role-reason reason)

           (nil? reason)
           (dissoc :role-reason))))))

(defn end-member
  [member action {:keys [actor active-members reason]} now]
  (let-nom>
    [_ (ensure-member-status member #{active})
     _ (check-grant action actor {:target-role (:role member)})
     _ (check-not-last-owner active-members member nil)]
    (let [{:keys [status] :as ending} (ended action)]
      (cond-> (assoc member
                     :status status
                     :updated-at now
                     :updated-by (actor-record actor))

              (and (:reason ending) (some-reason reason))
              (assoc (:reason ending) (some-reason reason))))))

(defn new-invitation
  [{:keys [bank-id email role reason]}
   {:keys [actor member-emails invitations aggregates policies]}
   now]
  (let [email-lower (some-> email
                            str/lower-case)
        invitation-id (utility/generate-id "inv")]
    (let-nom>
      [_ (check-grant :invite actor {:role role})
       _ (check-reason actor reason)
       _ (check-not-member-email member-emails email-lower)
       _ (check-no-pending invitations email-lower now)
       _ (check-member-limit aggregates policies)
       _ (check-pending-limit aggregates policies)
       _ (check-sent-limit aggregates policies)
       _ (check-email-limit aggregates policies)]
      (utility/assoc-some {:invitation-id invitation-id
                           :bank-id bank-id
                           :email email
                           :email-lower email-lower
                           :role role
                           :status pending
                           :token-hash invitation-id
                           :expires-at (+ now invitation-lifetime-ms)
                           :sent-on (day-of now)
                           :send-count 1
                           :created-at now
                           :created-by (actor-record actor)}
                          :reason
                          (some-reason reason)))))

(defn accept-invitation
  [invitation actor {:keys [active-members aggregates policies]} now]
  (let-nom>
    [_ (ensure-invitation-status invitation #{pending} now)
     _ (check-not-member active-members (:principal-id actor))
     _ (check-member-limit aggregates policies)]
    (assoc invitation
           :status accepted
           :updated-by (actor-record actor)
           :updated-at now)))

(defn decline-invitation
  [invitation actor now]
  (let-nom>
    [_ (ensure-invitation-status invitation #{pending} now)]
    (assoc invitation
           :status declined
           :updated-by (actor-record actor)
           :updated-at now)))

(defn withdraw-invitation
  [invitation {:keys [actor reason]} now]
  (let-nom>
    [_ (ensure-invitation-status invitation #{pending} now)]
    (utility/assoc-some (assoc invitation
                               :status withdrawn
                               :updated-by (actor-record actor)
                               :updated-at now)
                        :withdrawn-reason
                        (some-reason reason))))

(defn resend-invitation
  [invitation actor {:keys [aggregates policies]} now]
  (let-nom>
    [_ (ensure-invitation-status invitation #{pending expired} now)
     _ (check-sent-limit aggregates policies)
     _ (check-email-limit aggregates policies)]
    (let [today (day-of now)
          {:keys [sent-on send-count]} invitation]
      (assoc invitation
             :status pending
             :token-hash (:invitation-id invitation)
             :expires-at (+ now invitation-lifetime-ms)
             :sent-on today
             :send-count (if (= today sent-on) (inc send-count) 1)
             :updated-by (actor-record actor)
             :updated-at now))))

(defn record-invitation-token
  [invitation expires-at token-hash now]
  (let-nom> [_ (ensure-invitation-status invitation #{pending} now)
             _
             (when-not (= expires-at (:expires-at invitation))
               (error/reject :invitation/superseded
                             {:message "The invitation was sent again since"
                              :invitation-id (:invitation-id invitation)}))]
    (-> invitation
        (assoc :token-hash token-hash :updated-at now)
        (dissoc :updated-by))))
