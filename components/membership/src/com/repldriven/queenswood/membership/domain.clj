(ns com.repldriven.queenswood.membership.domain
  (:require
    [com.repldriven.queenswood.membership-query.interface :as q]
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
    (error/unauthorized :membership/role-not-granted
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

(def ^:private active :membership-status-active)

(def ^:private ended-status
  {:remove :membership-status-removed :leave :membership-status-left})

(defn- some-reason
  [reason]
  (when-not (str/blank? reason) reason))

(defn- active-owner?
  [membership]
  (and (= owner (:role membership)) (= active (:status membership))))

(defn check-not-last-owner
  [active-memberships target new-role]
  (when (and (active-owner? target)
             (not= owner new-role)
             (not-any? #(and (active-owner? %)
                             (not= (:membership-id target) (:membership-id %)))
                       active-memberships))
    (error/reject :membership/last-owner
                  {:message "Make someone else an owner first"
                   :membership-id (:membership-id target)})))

(defn check-not-member
  [active-memberships user-id]
  (when (some #(and (= user-id (:user-id %)) (= active (:status %)))
              active-memberships)
    (error/reject :membership/already-exists
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

(defn ensure-membership-status
  [membership allowed]
  (when-not (contains? allowed (:status membership))
    (error/reject :membership/invalid-status
                  {:message "Membership is not in a state that allows this"
                   :membership-id (:membership-id membership)
                   :status (:status membership)
                   :allowed allowed})))

(defn new-membership
  [{:keys [user-id bank-id role invitation-id actor]} now]
  (utility/assoc-some {:membership-id (utility/generate-id "mem")
                       :user-id user-id
                       :bank-id bank-id
                       :role (or role owner)
                       :status active
                       :created-at now
                       :created-by (actor-record actor)
                       :updated-at now}
                      :invitation-id
                      invitation-id))

(defn change-role
  [membership new-role {:keys [actor active-memberships]} now]
  (let-nom>
    [_ (ensure-membership-status membership #{active})
     _ (check-grant :change-role
                    actor
                    {:target-role (:role membership) :new-role new-role})
     _ (check-not-last-owner active-memberships membership new-role)]
    (if (= new-role (:role membership))
      membership
      (assoc membership :role new-role :updated-at now))))

(defn new-role-change
  [membership new-role {:keys [actor reason]} now]
  (let [{:keys [bank-id membership-id role]} membership]
    (utility/assoc-some {:bank-id bank-id
                         :membership-id membership-id
                         :role-change-id (utility/generate-id "rch")
                         :role-before role
                         :role-after new-role
                         :created-at now
                         :created-by (actor-record actor)}
                        :reason
                        (some-reason reason))))

(defn end-membership
  [membership action {:keys [actor active-memberships reason]} now]
  (let-nom>
    [_ (ensure-membership-status membership #{active})
     _ (check-grant action actor {:target-role (:role membership)})
     _ (check-not-last-owner active-memberships membership nil)]
    (utility/assoc-some (assoc membership
                               :status (ended-status action)
                               :ended-at now
                               :ended-by (actor-record actor)
                               :updated-at now)
                        :ended-reason
                        (some-reason reason))))

(defn new-invitation
  [{:keys [bank-id email role reason]}
   {:keys [actor member-emails invitations]}
   now]
  (let [email-lower (some-> email
                            str/lower-case)
        invitation-id (utility/generate-id "inv")]
    (let-nom>
      [_ (check-grant :invite actor {:role role})
       _ (check-reason actor reason)
       _ (check-not-member-email member-emails email-lower)
       _ (check-no-pending invitations email-lower now)]
      (utility/assoc-some {:invitation-id invitation-id
                           :bank-id bank-id
                           :email email
                           :email-lower email-lower
                           :role role
                           :status pending
                           :token-hash invitation-id
                           :expires-at (+ now invitation-lifetime-ms)
                           :created-at now
                           :created-by (actor-record actor)
                           :updated-at now}
                          :reason
                          (some-reason reason)))))

(defn accept-invitation
  [invitation actor {:keys [active-memberships]} now]
  (let-nom>
    [_ (ensure-invitation-status invitation #{pending} now)
     _ (check-not-member active-memberships (:principal-id actor))]
    (assoc invitation
           :status accepted
           :accepted-at now
           :accepted-by (actor-record actor)
           :updated-at now)))

(defn decline-invitation
  [invitation actor now]
  (let-nom>
    [_ (ensure-invitation-status invitation #{pending} now)]
    (assoc invitation
           :status declined
           :declined-at now
           :declined-by (actor-record actor)
           :updated-at now)))

(defn withdraw-invitation
  [invitation {:keys [actor reason]} now]
  (let-nom>
    [_ (ensure-invitation-status invitation #{pending} now)]
    (utility/assoc-some (assoc invitation
                               :status withdrawn
                               :withdrawn-at now
                               :withdrawn-by (actor-record actor)
                               :updated-at now)
                        :withdrawn-reason
                        (some-reason reason))))

(defn resend-invitation
  [invitation actor now]
  (let-nom>
    [_ (ensure-invitation-status invitation #{pending expired} now)]
    (assoc invitation
           :status pending
           :token-hash (:invitation-id invitation)
           :expires-at (+ now invitation-lifetime-ms)
           :resent-at now
           :resent-by (actor-record actor)
           :updated-at now)))

(defn record-invitation-token
  [invitation expires-at token-hash now]
  (let-nom> [_ (ensure-invitation-status invitation #{pending} now)
             _
             (when-not (= expires-at (:expires-at invitation))
               (error/reject :invitation/superseded
                             {:message "The invitation was sent again since"
                              :invitation-id (:invitation-id invitation)}))]
    (assoc invitation
           :token-hash token-hash
           :updated-at now)))
