(ns com.repldriven.queenswood.membership.domain
  (:require
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

(defn- membership-not-found
  [membership-id]
  (error/reject :membership/not-found
                {:message "Membership not found"
                 :membership-id membership-id}))

(defn ensure-found
  [membership membership-id]
  (or membership (membership-not-found membership-id)))

(defn check-in-bank
  [membership bank-id]
  (when-not (= bank-id (:bank-id membership))
    (membership-not-found (:membership-id membership))))

(defn check-own
  [membership user-id]
  (when-not (= user-id (:user-id membership))
    (membership-not-found (:membership-id membership))))

(def ^:private active :membership-status-active)
(def ^:private ended :membership-status-ended)

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

(defn effective-status
  [invitation now]
  (let [{:keys [status expires-at]} invitation]
    (if (and (= pending status) expires-at (<= expires-at now))
      expired
      status)))

(defn check-no-pending
  [invitations email-lower now]
  (when-let [invitation (some #(when (and (= email-lower (:email-lower %))
                                          (= pending (effective-status % now)))
                                 %)
                              invitations)]
    (error/reject :invitation/already-exists
                  {:message "That address has a pending invitation"
                   :invitation-id (:invitation-id invitation)})))

(defn ensure-invitation-status
  [invitation allowed now]
  (let [status (effective-status invitation now)]
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

(defn check-recipient
  [invitation {:keys [token-hash email email-verified?]}]
  (when-not (or (and (some? token-hash)
                     (= token-hash (:token-hash invitation)))
                (and (true? email-verified?)
                     (some? email)
                     (= (str/lower-case email) (:email-lower invitation))))
    (error/reject :invitation/not-found
                  {:message "Invitation not found"
                   :invitation-id (:invitation-id invitation)})))

(defn new-membership
  [{:keys [user-id bank-id role invitation-id]} now]
  (utility/assoc-some {:membership-id (utility/generate-id "mem")
                       :user-id user-id
                       :bank-id bank-id
                       :role (or role owner)
                       :status active
                       :created-at now
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
    (assoc membership :role new-role :updated-at now)))

(defn end-membership
  [membership action {:keys [actor active-memberships]} now]
  (let-nom>
    [_ (ensure-membership-status membership #{active})
     _ (check-grant action actor {:target-role (:role membership)})
     _ (check-not-last-owner active-memberships membership nil)]
    (assoc membership
           :status ended
           :ended-at now
           :ended-by (actor-record actor)
           :updated-at now)))

(defn new-invitation
  [{:keys [bank-id email role reason]}
   {:keys [actor member-emails invitations]}
   token-hash
   now]
  (let [email-lower (some-> email
                            str/lower-case)]
    (let-nom>
      [_ (check-grant :invite actor {:role role})
       _ (check-reason actor reason)
       _ (check-not-member-email member-emails email-lower)
       _ (check-no-pending invitations email-lower now)]
      (utility/assoc-some {:invitation-id (utility/generate-id "inv")
                           :bank-id bank-id
                           :email email
                           :email-lower email-lower
                           :role role
                           :status pending
                           :token-hash token-hash
                           :expires-at (+ now invitation-lifetime-ms)
                           :invited-by (actor-record actor)
                           :created-at now
                           :updated-at now}
                          :reason
                          (when-not (str/blank? reason) reason)))))

(defn accept-invitation
  [invitation user-id {:keys [active-memberships]} now]
  (let-nom>
    [_ (ensure-invitation-status invitation #{pending} now)
     _ (check-not-member active-memberships user-id)]
    (assoc invitation
           :status accepted
           :accepted-by-user-id user-id
           :updated-at now)))

(defn decline-invitation
  [invitation now]
  (let-nom>
    [_ (ensure-invitation-status invitation #{pending} now)]
    (assoc invitation :status declined :updated-at now)))

(defn withdraw-invitation
  [invitation now]
  (let-nom>
    [_ (ensure-invitation-status invitation #{pending} now)]
    (assoc invitation :status withdrawn :updated-at now)))

(defn resend-invitation
  [invitation token-hash now]
  (let-nom>
    [_ (ensure-invitation-status invitation #{pending expired} now)]
    (assoc invitation
           :status pending
           :token-hash token-hash
           :expires-at (+ now invitation-lifetime-ms)
           :updated-at now)))

(defn new-access-event
  [bank-id kind actor details now]
  (into {:access-event-id (utility/generate-id "aev")
         :bank-id bank-id
         :kind kind
         :actor (actor-record actor)
         :occurred-at now}
        (remove (comp nil? val))
        (select-keys details
                     [:subject-user-id :membership-id :invitation-id :email
                      :role-before :role-after :reason])))
