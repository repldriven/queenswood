(ns com.repldriven.queenswood.membership-query.domain
  (:require
    [com.repldriven.mono.error.interface :as error]

    [clojure.string :as str]))

(def ^:private pending :invitation-status-pending)
(def ^:private expired :invitation-status-expired)

(defn effective-status
  [invitation now]
  (let [{:keys [status expires-at]} invitation]
    (if (and (= pending status) expires-at (<= expires-at now))
      expired
      status)))

(defn- membership-not-found
  [membership-id]
  (error/reject :membership/not-found
                {:message "Membership not found"
                 :membership-id membership-id}))

(defn ensure-found
  [membership membership-id]
  (or membership (membership-not-found membership-id)))

(defn- invitation-not-found
  [invitation-id]
  (error/reject :invitation/not-found
                {:message "Invitation not found"
                 :invitation-id invitation-id}))

(defn ensure-invitation-found
  [invitation invitation-id]
  (or invitation (invitation-not-found invitation-id)))

(defn check-recipient
  [invitation {:keys [token-hash email email-verified?]}]
  (when-not (or (and (some? token-hash)
                     (= token-hash (:token-hash invitation)))
                (and (true? email-verified?)
                     (some? email)
                     (= (str/lower-case email) (:email-lower invitation))))
    (invitation-not-found (:invitation-id invitation))))

(def ^:private crockford "0123456789abcdefghjkmnpqrstvwxyz")

(defn- time-chars
  [at]
  (apply str
         (map (fn [shift]
                (nth crockford (bit-and (bit-shift-right at shift) 31)))
              (range 45 -5 -5))))

(def ^:private kind-rank
  {:access-event-kind-bank-created 0
   :access-event-kind-invitation-created 1
   :access-event-kind-invitation-resent 2
   :access-event-kind-invitation-accepted 3
   :access-event-kind-invitation-declined 3
   :access-event-kind-invitation-withdrawn 3
   :access-event-kind-role-changed 4
   :access-event-kind-member-removed 5
   :access-event-kind-member-left 5})

(defn- tail-chars
  [source-id]
  (apply str
         (map (fn [c]
                (if (str/includes? crockford (str c))
                  c
                  (nth crockford (mod (int c) 32))))
              (take-last 15 (concat (repeat 15 \0) source-id)))))

(defn- event-id
  "An id that sorts as the event happened: when, then the kind's place
  among acts at the same instant, then the record it was read from."
  [kind at source-id]
  (str "aev."
       (time-chars at)
       (nth crockford (kind-rank kind))
       (tail-chars source-id)))

(defn- event
  [bank-id kind at actor source-id details]
  (into {:access-event-id (event-id kind at source-id)
         :bank-id bank-id
         :kind kind
         :actor actor
         :occurred-at at}
        (remove (comp nil? val))
        details))

(defn- invitation-events
  [invitation membership-ids]
  (let [{:keys [bank-id invitation-id email role reason created-at created-by
                resent-at resent-by accepted-at accepted-by declined-at
                declined-by withdrawn-at withdrawn-by withdrawn-reason]}
        invitation
        offer {:invitation-id invitation-id :email email :role-after role}
        act
        (fn [kind at by details]
          (when at
            (event bank-id kind at by invitation-id (merge offer details))))]
    (keep identity
          [(act :access-event-kind-invitation-created
                created-at
                created-by
                {:reason reason})
           (act :access-event-kind-invitation-resent resent-at resent-by {})
           (act :access-event-kind-invitation-accepted
                accepted-at
                accepted-by
                {:subject-user-id (:principal-id accepted-by)
                 :membership-id (get membership-ids invitation-id)})
           (act :access-event-kind-invitation-declined
                declined-at
                declined-by
                {:subject-user-id (:principal-id declined-by)})
           (act :access-event-kind-invitation-withdrawn
                withdrawn-at
                withdrawn-by
                {:reason withdrawn-reason})])))

(def ^:private ended-kind
  {:membership-status-removed :access-event-kind-member-removed
   :membership-status-left :access-event-kind-member-left})

(defn- ended-event
  [membership]
  (let [{:keys [bank-id membership-id user-id role status ended-at ended-by
                ended-reason]}
        membership]
    (when-let [kind (ended-kind status)]
      (event bank-id
             kind
             ended-at
             ended-by
             membership-id
             {:subject-user-id user-id
              :membership-id membership-id
              :role-before role
              :reason ended-reason}))))

(defn- role-change-event
  [role-change user-ids]
  (let [{:keys [bank-id membership-id role-change-id role-before role-after
                reason created-at created-by]}
        role-change]
    (event bank-id
           :access-event-kind-role-changed
           created-at
           created-by
           role-change-id
           {:subject-user-id (get user-ids membership-id)
            :membership-id membership-id
            :role-before role-before
            :role-after role-after
            :reason reason})))

(defn- first-role
  "The role `membership` was created with: the one its earliest role
  change moved it from, or its role when it has had none."
  [membership role-changes]
  (or (->> role-changes
           (filter #(= (:membership-id membership) (:membership-id %)))
           (sort-by :created-at)
           first
           :role-before)
      (:role membership)))

(defn- bank-created-event
  [bank memberships role-changes]
  (let [{:keys [bank-id created-at created-by]} bank
        owner (->> memberships
                   (remove :invitation-id)
                   (sort-by :created-at)
                   first)]
    (event bank-id
           :access-event-kind-bank-created
           created-at
           (into {} created-by)
           bank-id
           (when owner
             {:subject-user-id (:user-id owner)
              :membership-id (:membership-id owner)
              :role-after (first-role owner role-changes)}))))

(defn access-events
  "A bank's access history, newest first, read from the records each act
  was recorded on: the bank's creation, each invitation's acts, each
  role change, and each membership's end."
  [bank memberships invitations role-changes]
  (let [membership-ids (into {}
                             (keep (fn [{:keys [invitation-id membership-id]}]
                                     (when invitation-id
                                       [invitation-id membership-id])))
                             memberships)
        user-ids (into {}
                       (map (juxt :membership-id :user-id))
                       memberships)]
    (->> (concat [(bank-created-event bank memberships role-changes)]
                 (mapcat #(invitation-events % membership-ids) invitations)
                 (map #(role-change-event % user-ids) role-changes)
                 (keep ended-event memberships))
         (sort-by :access-event-id #(compare %2 %1))
         vec)))
