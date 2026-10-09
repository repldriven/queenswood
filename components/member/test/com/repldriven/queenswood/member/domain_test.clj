(ns com.repldriven.queenswood.member.domain-test
  (:require
    [com.repldriven.queenswood.member.domain :as SUT]

    [com.repldriven.mono.error.interface :as error]

    [clojure.test :refer [deftest is testing]]))

(def ^:private now 1789000000000)

(def ^:private day-ms (* 24 60 60 1000))

(def ^:private roles [:role-owner :role-admin :role-developer :role-viewer])

(def ^:private all-roles (set roles))

(def ^:private below-owner #{:role-admin :role-developer :role-viewer})

(defn- member-actor
  [role]
  {:kind :actor-kind-member :principal-id (str "usr." (name role)) :role role})

(def ^:private operator-actor
  {:kind :actor-kind-operator :principal-id "ops.1"})

(def ^:private actors
  {:owner (member-actor :role-owner)
   :admin (member-actor :role-admin)
   :developer (member-actor :role-developer)
   :viewer (member-actor :role-viewer)
   :operator operator-actor})

(def ^:private grantable
  {:owner all-roles
   :admin below-owner
   :developer #{}
   :viewer #{}
   :operator all-roles})

(defn- member
  ([id user-id role] (member id user-id role :member-status-active))
  ([id user-id role status]
   {:member-id id
    :user-id user-id
    :bank-id "bnk.1"
    :role role
    :status status
    :created-at 1
    :updated-at 1}))

(defn- invitation
  [status expires-at]
  {:invitation-id "inv.1"
   :bank-id "bnk.1"
   :email "Ada@Example.test"
   :email-lower "ada@example.test"
   :role :role-developer
   :status status
   :token-hash "hash-1"
   :expires-at expires-at
   :created-at 1
   :created-by {:kind :actor-kind-member :principal-id "usr.owner"}
   :updated-at 1})

(defn- recipient
  [user-id]
  {:kind :actor-kind-member :principal-id user-id})

(defn- not-granted?
  [result]
  (and (error/unauthorized? result)
       (= :membership/role-not-granted (error/kind result))))

(defn- rejected?
  [kind result]
  (and (error/rejection? result) (= kind (error/kind result))))

(defn- mentions?
  [x value]
  (boolean (some #{value} (tree-seq coll? seq x))))

(deftest check-grant-invite-test
  (doseq [[actor-name actor] actors
          role roles]
    (testing (str (name actor-name) " inviting " (name role))
      (let [allowed? (contains? (grantable actor-name) role)
            result (SUT/check-grant :invite actor {:role role})]
        (is (= allowed? (SUT/may-invite? actor role)))
        (if allowed? (is (nil? result)) (is (not-granted? result)))))))

(deftest check-grant-change-role-test
  (doseq [[actor-name actor] actors
          target-role roles
          new-role roles]
    (testing (str (name actor-name)
                  " setting " (name target-role)
                  " to " (name new-role))
      (let [grants (grantable actor-name)
            allowed? (and (contains? grants target-role)
                          (contains? grants new-role))
            result (SUT/check-grant :change-role
                                    actor
                                    {:target-role target-role
                                     :new-role new-role})]
        (is (= allowed? (SUT/may-change-role? actor target-role new-role)))
        (if allowed? (is (nil? result)) (is (not-granted? result)))))))

(deftest check-grant-remove-test
  (doseq [[actor-name actor] actors
          target-role roles]
    (testing (str (name actor-name) " removing " (name target-role))
      (let [allowed? (contains? (grantable actor-name) target-role)
            result (SUT/check-grant :remove actor {:target-role target-role})]
        (is (= allowed? (SUT/may-remove? actor target-role)))
        (if allowed? (is (nil? result)) (is (not-granted? result)))))))

(deftest check-grant-leave-test
  (doseq [[actor-name actor] actors]
    (testing (str (name actor-name) " leaving")
      (let [result (SUT/check-grant :leave actor {:target-role (:role actor)})]
        (if (= :operator actor-name)
          (is (not-granted? result))
          (is (nil? result))))))
  (testing "an unknown action is refused"
    (is (not-granted? (SUT/check-grant :promote (:owner actors) {})))))

(deftest check-grant-payload-test
  (let [result (SUT/check-grant :change-role
                                (:admin actors)
                                {:target-role :role-admin
                                 :new-role :role-owner})]
    (is (= {:action :change-role
            :actor-role :role-admin
            :target-role :role-admin
            :new-role :role-owner}
           (dissoc (error/payload result) :message)))
    (is (string? (:message (error/payload result))))))

(deftest check-reason-test
  (testing "an operator's invitation without a reason is refused"
    (is (rejected? :invitation/reason-required
                   (SUT/check-reason operator-actor nil)))
    (is (rejected? :invitation/reason-required
                   (SUT/check-reason operator-actor "  "))))
  (testing "an operator's invitation with a reason passes"
    (is (nil? (SUT/check-reason operator-actor "Founder handover"))))
  (testing "a member needs no reason"
    (is (nil? (SUT/check-reason (:owner actors) nil))))
  (testing "new-invitation refuses an operator with no reason"
    (is (rejected? :invitation/reason-required
                   (SUT/new-invitation
                    {:bank-id "bnk.1" :email "a@example.test" :role :role-owner}
                    {:actor operator-actor}
                    now)))))

(deftest last-owner-with-one-owner-test
  (let [founder (member "mem.1" "usr.owner" :role-owner)
        admin (member "mem.2" "usr.admin" :role-admin)
        active [founder admin]
        member-ctx {:actor (:owner actors) :active-members active}
        operator-ctx {:actor operator-actor :active-members active}]
    (doseq [[label ctx] [["an owner" member-ctx] ["an operator" operator-ctx]]]
      (testing (str label " demoting the only owner is refused")
        (let [result (SUT/change-role founder :role-admin ctx now)]
          (is (rejected? :membership/last-owner result))
          (is (= "Make someone else an owner first"
                 (:message (error/payload result))))))
      (testing (str label " removing the only owner is refused")
        (is (rejected? :membership/last-owner
                       (SUT/end-member founder :remove ctx now)))))
    (testing "the only owner leaving is refused"
      (is (rejected? :membership/last-owner
                     (SUT/end-member founder :leave member-ctx now))))
    (testing "setting the only owner to owner changes no owner count"
      (is (nil? (SUT/check-not-last-owner active founder :role-owner))))
    (testing "a non-owner target is never the last owner"
      (is (nil? (SUT/check-not-last-owner [admin] admin nil))))))

(deftest last-owner-with-two-owners-test
  (let [a (member "mem.a" "usr.a" :role-owner)
        b (member "mem.b" "usr.b" :role-owner)]
    (doseq [[label actor] [["an owner" (:owner actors)]
                           ["an operator" operator-actor]]]
      (testing (str label " demotes one owner, then not the other")
        (let [demoted (SUT/change-role a
                                       :role-admin
                                       {:actor actor :active-members [a b]}
                                       now)]
          (is (= :role-admin (:role demoted)))
          (is (rejected? :membership/last-owner
                         (SUT/change-role b
                                          :role-admin
                                          {:actor actor
                                           :active-members [demoted b]}
                                          now)))))
      (testing (str label " removes one owner, then not the other")
        (let [removed (SUT/end-member a
                                      :remove
                                      {:actor actor :active-members [a b]}
                                      now)]
          (is (= :member-status-removed (:status removed)))
          (is (rejected? :membership/last-owner
                         (SUT/end-member b
                                         :remove
                                         {:actor actor
                                          :active-members [removed b]}
                                         now))))))
    (testing "one owner leaves, then the other may not"
      (let [left (SUT/end-member a
                                 :leave
                                 {:actor (member-actor :role-owner)
                                  :active-members [a b]}
                                 now)]
        (is (= :member-status-left (:status left)))
        (is (rejected? :membership/last-owner
                       (SUT/end-member b
                                       :leave
                                       {:actor (member-actor :role-owner)
                                        :active-members [left b]}
                                       now)))))))

(deftest member-guard-test
  (let [ended (member "mem.1" "usr.1" :role-viewer :member-status-removed)
        ctx {:actor (:owner actors) :active-members []}]
    (doseq [[label result] [["change-role"
                             (SUT/change-role ended :role-admin ctx now)]
                            ["remove"
                             (SUT/end-member ended :remove ctx now)]
                            ["leave"
                             (SUT/end-member ended :leave ctx now)]]]
      (testing (str label " refuses an ended member with its payload")
        (is (rejected? :membership/invalid-status result))
        (is (= {:member-id "mem.1"
                :status :member-status-removed
                :allowed #{:member-status-active}}
               (dissoc (error/payload result) :message)))
        (is (string? (:message (error/payload result))))))
    (testing "the source state is checked before the grant"
      (is (rejected? :membership/invalid-status
                     (SUT/change-role ended
                                      :role-admin
                                      {:actor (:viewer actors)
                                       :active-members []}
                                      now))))))

(deftest unchanged-role-test
  (let [owner-m (member "mem.1" "usr.owner" :role-owner)
        admin-m (member "mem.2" "usr.admin" :role-admin)
        active [owner-m admin-m]]
    (testing "an owner setting a member to its current role gets it back"
      (let [result (SUT/change-role admin-m
                                    :role-admin
                                    {:actor (:owner actors)
                                     :active-members active}
                                    now)]
        (is (= admin-m result))
        (is (= 1 (:updated-at result)))))
    (testing "an admin naming owner on an owner is still not granted"
      (is (not-granted? (SUT/change-role owner-m
                                         :role-owner
                                         {:actor (:admin actors)
                                          :active-members active}
                                         now))))
    (testing "an ended member is still refused on its status"
      (is (rejected? :membership/invalid-status
                     (SUT/change-role
                      (assoc admin-m :status :member-status-removed)
                      :role-admin
                      {:actor (:owner actors) :active-members active}
                      now))))))

(deftest invitation-guard-test
  (let [pending (invitation :invitation-status-pending (+ now day-ms))
        lapsed (invitation :invitation-status-pending (- now day-ms))
        terminal (for [status [:invitation-status-accepted
                               :invitation-status-declined
                               :invitation-status-withdrawn]]
                   [status (invitation status (+ now day-ms))])
        wrong-for-pending (conj (vec terminal)
                                [:invitation-status-expired lapsed])
        transitions
        {"accept" #(SUT/accept-invitation %
                                          (recipient "usr.new")
                                          {:active-members []}
                                          now)
         "decline" #(SUT/decline-invitation % (recipient "usr.new") now)
         "withdraw" #(SUT/withdraw-invitation % {:actor (:owner actors)} now)}]
    (doseq [[label transition] transitions
            [status inv] wrong-for-pending]
      (testing (str label " refuses " (name status) " with its payload")
        (let [result (transition inv)]
          (is (rejected? :invitation/invalid-status result))
          (is (= {:invitation-id "inv.1"
                  :status status
                  :allowed #{:invitation-status-pending}}
                 (dissoc (error/payload result) :message)))
          (is (string? (:message (error/payload result))))
          (is (not (mentions? result "hash-1"))))))
    (doseq [[status inv] terminal]
      (testing (str "resend refuses " (name status) " with its payload")
        (let [result (SUT/resend-invitation inv (:owner actors) now)]
          (is (rejected? :invitation/invalid-status result))
          (is (= {:invitation-id "inv.1"
                  :status status
                  :allowed #{:invitation-status-pending
                             :invitation-status-expired}}
                 (dissoc (error/payload result) :message)))
          (is (not (mentions? result "hash-1"))))))
    (testing "accept, decline and withdraw move a pending invitation"
      (is (= {:status :invitation-status-accepted
              :accepted-at now
              :accepted-by (recipient "usr.new")
              :updated-at now}
             (select-keys (SUT/accept-invitation pending
                                                 (recipient "usr.new")
                                                 {:active-members []}
                                                 now)
                          [:status :accepted-at :accepted-by :updated-at])))
      (is (= {:status :invitation-status-declined
              :declined-at now
              :declined-by (recipient "usr.new")}
             (select-keys
              (SUT/decline-invitation pending (recipient "usr.new") now)
              [:status :declined-at :declined-by])))
      (is (= {:status :invitation-status-withdrawn
              :withdrawn-at now
              :withdrawn-by operator-actor
              :withdrawn-reason "Sent to the wrong address"}
             (select-keys (SUT/withdraw-invitation pending
                                                   {:actor operator-actor
                                                    :reason
                                                    "Sent to the wrong address"}
                                                   now)
                          [:status :withdrawn-at :withdrawn-by
                           :withdrawn-reason]))))
    (testing "withdraw omits a blank reason"
      (is (not (contains? (SUT/withdraw-invitation pending
                                                   {:actor operator-actor
                                                    :reason " "}
                                                   now)
                          :withdrawn-reason))))
    (testing "resend renews a pending invitation, recording the latest resend"
      (let [resent (SUT/resend-invitation pending operator-actor now)]
        (is (= "inv.1" (:token-hash resent)))
        (is (= (+ now SUT/invitation-lifetime-ms) (:expires-at resent)))
        (is (= now (:resent-at resent)))
        (is (= operator-actor (:resent-by resent)))))))

(deftest one-active-member-test
  (let [existing (member "mem.1" "usr.1" :role-viewer)
        ended (member "mem.2" "usr.2" :role-viewer :member-status-removed)
        pending (invitation :invitation-status-pending (+ now day-ms))]
    (testing "an active member of the bank is refused"
      (is (rejected? :membership/already-exists
                     (SUT/check-not-member [existing] "usr.1")))
      (is (rejected? :membership/already-exists
                     (SUT/accept-invitation pending
                                            (recipient "usr.1")
                                            {:active-members [existing]}
                                            now))))
    (testing "an ended member does not count"
      (is (nil? (SUT/check-not-member [ended] "usr.2"))))
    (testing "an address held by an active member is refused, in any case"
      (is (rejected? :invitation/already-member
                     (SUT/check-not-member-email ["Ada@Example.test"]
                                                 "ada@example.test")))
      (is (nil? (SUT/check-not-member-email ["bob@example.test"]
                                            "ada@example.test"))))
    (testing "an address with a pending invitation is refused"
      (let [result (SUT/check-no-pending [pending] "ada@example.test" now)]
        (is (rejected? :invitation/already-exists result))
        (is (not (mentions? result "hash-1")))))
    (testing "an expired or answered invitation does not block another"
      (is (nil? (SUT/check-no-pending
                 [(invitation :invitation-status-pending (- now day-ms))
                  (invitation :invitation-status-declined (+ now day-ms))]
                 "ada@example.test"
                 now))))
    (testing "new-invitation runs the address rules"
      (let [ctx
            {:actor (:owner actors) :member-emails [] :invitations [pending]}
            input
            {:bank-id "bnk.1" :email "ADA@example.test" :role :role-viewer}]
        (is (rejected? :invitation/already-exists
                       (SUT/new-invitation input ctx now)))
        (is (rejected? :invitation/already-member
                       (SUT/new-invitation
                        input
                        (assoc ctx :member-emails ["ada@example.test"])
                        now)))
        (is (not-granted? (SUT/new-invitation (assoc input :role :role-owner)
                                              (assoc ctx :actor (:admin actors))
                                              now)))))))

(deftest expiry-test
  (let [expires-at (+ now day-ms)
        pending (invitation :invitation-status-pending expires-at)]
    (testing "the lifetime is seven days"
      (is (= (* 7 day-ms) SUT/invitation-lifetime-ms)))
    (testing "accept past expiry is refused as expired"
      (let [result (SUT/accept-invitation pending
                                          (recipient "usr.new")
                                          {:active-members []}
                                          (+ expires-at day-ms))]
        (is (rejected? :invitation/invalid-status result))
        (is (= :invitation-status-expired (:status (error/payload result))))))
    (testing "resend of an expired invitation is allowed and renews it"
      (let [later (+ expires-at (* 2 day-ms))
            resent (SUT/resend-invitation pending (:owner actors) later)]
        (is (not (error/anomaly? resent)))
        (is (= :invitation-status-pending (:status resent)))
        (is (= (+ later SUT/invitation-lifetime-ms) (:expires-at resent)))
        (is (= "inv.1" (:token-hash resent)))))))

(deftest record-invitation-token-test
  (let [expires-at (+ now day-ms)
        pending (invitation :invitation-status-pending expires-at)]
    (testing "a pending invitation takes the hash, replacing the earlier one"
      (is (= (assoc pending :token-hash "hash-2" :updated-at now)
             (SUT/record-invitation-token pending expires-at "hash-2" now))))
    (testing "an invitation sent again since is superseded"
      (let [result
            (SUT/record-invitation-token pending (dec expires-at) "hash-2" now)]
        (is (rejected? :invitation/superseded result))
        (is (not (mentions? result "hash-2")))))
    (testing "an expired invitation is refused"
      (is
       (rejected?
        :invitation/invalid-status
        (SUT/record-invitation-token pending expires-at "hash-2" expires-at))))
    (testing "an answered invitation is refused"
      (is (rejected? :invitation/invalid-status
                     (SUT/record-invitation-token
                      (assoc pending :status :invitation-status-withdrawn)
                      expires-at
                      "hash-2"
                      now))))))

(deftest constructors-test
  (testing "new-member builds an active member at now"
    (let [m (SUT/new-member {:user-id "usr.1"
                             :bank-id "bnk.1"
                             :role :role-developer
                             :invitation-id "inv.1"
                             :actor {:kind :actor-kind-member
                                     :principal-id "usr.1"
                                     :role :role-owner}}
                            now)]
      (is (re-find #"^mem\." (:member-id m)))
      (is (= {:user-id "usr.1"
              :bank-id "bnk.1"
              :role :role-developer
              :status :member-status-active
              :invitation-id "inv.1"
              :created-at now
              :created-by {:kind :actor-kind-member :principal-id "usr.1"}
              :updated-at now}
             (dissoc m :member-id)))))
  (testing "new-member defaults to owner and omits a missing invitation"
    (let [m (SUT/new-member {:user-id "usr.1" :bank-id "bnk.1"} now)]
      (is (= :role-owner (:role m)))
      (is (not (contains? m :invitation-id)))))
  (testing "new-invitation builds a pending invitation for seven days"
    (let [inv (SUT/new-invitation
               {:bank-id "bnk.1"
                :email "Ada@Example.test"
                :role :role-owner
                :reason "Founder handover"}
               {:actor operator-actor :member-emails [] :invitations []}
               now)]
      (is (re-find #"^inv\." (:invitation-id inv)))
      (is (= {:bank-id "bnk.1"
              :email "Ada@Example.test"
              :email-lower "ada@example.test"
              :role :role-owner
              :status :invitation-status-pending
              :token-hash (:invitation-id inv)
              :expires-at (+ now SUT/invitation-lifetime-ms)
              :reason "Founder handover"
              :created-at now
              :created-by {:kind :actor-kind-operator :principal-id "ops.1"}
              :updated-at now}
             (dissoc inv :invitation-id)))))
  (testing "new-invitation omits a blank reason and the actor's role"
    (let [inv (SUT/new-invitation {:bank-id "bnk.1"
                                   :email "a@example.test"
                                   :role :role-viewer
                                   :reason ""}
                                  {:actor (:admin actors)}
                                  now)]
      (is (not (contains? inv :reason)))
      (is (= {:kind :actor-kind-member :principal-id "usr.role-admin"}
             (:created-by inv)))))
  (testing "change-role sets the role at now"
    (let [m (member "mem.1" "usr.1" :role-viewer)]
      (is (= (assoc m :role :role-developer :updated-at now)
             (SUT/change-role m
                              :role-developer
                              {:actor (:admin actors) :active-members [m]}
                              now)))))
  (testing "end-member records how, when, by whom and why"
    (let [m (member "mem.1" "usr.1" :role-viewer)]
      (is (= (assoc m
                    :status :member-status-removed
                    :ended-at now
                    :ended-by {:kind :actor-kind-operator :principal-id "ops.1"}
                    :ended-reason "Left the company"
                    :updated-at now)
             (SUT/end-member m
                             :remove
                             {:actor operator-actor
                              :active-members [m]
                              :reason "Left the company"}
                             now)))
      (is (= :member-status-left
             (:status (SUT/end-member m
                                      :leave
                                      {:actor (recipient "usr.1")
                                       :active-members [m]}
                                      now))))))
  (testing "new-role-change records the move, by whom and why"
    (let [m (member "mem.1" "usr.1" :role-viewer)
          change (SUT/new-role-change m
                                      :role-admin
                                      {:actor (:owner actors)
                                       :reason "Leads the team"}
                                      now)]
      (is (re-find #"^rch\." (:role-change-id change)))
      (is (= {:bank-id "bnk.1"
              :member-id "mem.1"
              :role-before :role-viewer
              :role-after :role-admin
              :reason "Leads the team"
              :created-at now
              :created-by {:kind :actor-kind-member
                           :principal-id "usr.role-owner"}}
             (dissoc change :role-change-id))))))
