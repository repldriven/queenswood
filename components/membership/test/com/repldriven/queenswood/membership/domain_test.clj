(ns com.repldriven.queenswood.membership.domain-test
  (:require
    [com.repldriven.queenswood.membership.domain :as SUT]

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

(defn- membership
  ([id user-id role] (membership id user-id role :membership-status-active))
  ([id user-id role status]
   {:membership-id id
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
   :invited-by {:kind :actor-kind-member :principal-id "usr.owner"}
   :created-at 1
   :updated-at 1})

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
                    "hash-1"
                    now)))))

(deftest check-in-bank-test
  (let [m (membership "mem.1" "usr.1" :role-viewer)]
    (testing "a membership of the actor's bank passes"
      (is (nil? (SUT/check-in-bank m "bnk.1"))))
    (testing "a membership of another bank is not found"
      (let [result (SUT/check-in-bank m "bnk.2")]
        (is (rejected? :membership/not-found result))
        (is (= "mem.1" (:membership-id (error/payload result))))
        (is (not (mentions? result "bnk.1")))))))

(deftest ensure-found-test
  (let [m (membership "mem.1" "usr.1" :role-viewer)]
    (testing "a loaded membership is returned"
      (is (= m (SUT/ensure-found m "mem.1"))))
    (testing "no membership is not found"
      (let [result (SUT/ensure-found nil "mem.9")]
        (is (rejected? :membership/not-found result))
        (is (= "mem.9" (:membership-id (error/payload result))))))))

(deftest check-own-test
  (let [m (membership "mem.1" "usr.1" :role-viewer)]
    (testing "the caller's own membership passes"
      (is (nil? (SUT/check-own m "usr.1"))))
    (testing "someone else's membership is not found"
      (is (rejected? :membership/not-found (SUT/check-own m "usr.2"))))))

(deftest last-owner-with-one-owner-test
  (let [founder (membership "mem.1" "usr.owner" :role-owner)
        admin (membership "mem.2" "usr.admin" :role-admin)
        active [founder admin]
        member-ctx {:actor (:owner actors) :active-memberships active}
        operator-ctx {:actor operator-actor :active-memberships active}]
    (doseq [[label ctx] [["an owner" member-ctx] ["an operator" operator-ctx]]]
      (testing (str label " demoting the only owner is refused")
        (let [result (SUT/change-role founder :role-admin ctx now)]
          (is (rejected? :membership/last-owner result))
          (is (= "Make someone else an owner first"
                 (:message (error/payload result))))))
      (testing (str label " removing the only owner is refused")
        (is (rejected? :membership/last-owner
                       (SUT/end-membership founder :remove ctx now)))))
    (testing "the only owner leaving is refused"
      (is (rejected? :membership/last-owner
                     (SUT/end-membership founder :leave member-ctx now))))
    (testing "setting the only owner to owner changes no owner count"
      (is (nil? (SUT/check-not-last-owner active founder :role-owner))))
    (testing "a non-owner target is never the last owner"
      (is (nil? (SUT/check-not-last-owner [admin] admin nil))))))

(deftest last-owner-with-two-owners-test
  (let [a (membership "mem.a" "usr.a" :role-owner)
        b (membership "mem.b" "usr.b" :role-owner)]
    (doseq [[label actor] [["an owner" (:owner actors)]
                           ["an operator" operator-actor]]]
      (testing (str label " demotes one owner, then not the other")
        (let [demoted (SUT/change-role a
                                       :role-admin
                                       {:actor actor :active-memberships [a b]}
                                       now)]
          (is (= :role-admin (:role demoted)))
          (is (rejected? :membership/last-owner
                         (SUT/change-role b
                                          :role-admin
                                          {:actor actor
                                           :active-memberships [demoted b]}
                                          now)))))
      (testing (str label " removes one owner, then not the other")
        (let [removed (SUT/end-membership a
                                          :remove
                                          {:actor actor
                                           :active-memberships [a b]}
                                          now)]
          (is (= :membership-status-ended (:status removed)))
          (is (rejected? :membership/last-owner
                         (SUT/end-membership b
                                             :remove
                                             {:actor actor
                                              :active-memberships [removed b]}
                                             now))))))
    (testing "one owner leaves, then the other may not"
      (let [left (SUT/end-membership a
                                     :leave
                                     {:actor (member-actor :role-owner)
                                      :active-memberships [a b]}
                                     now)]
        (is (= :membership-status-ended (:status left)))
        (is (rejected? :membership/last-owner
                       (SUT/end-membership b
                                           :leave
                                           {:actor (member-actor :role-owner)
                                            :active-memberships [left b]}
                                           now)))))))

(deftest membership-guard-test
  (let [ended (membership "mem.1" "usr.1" :role-viewer :membership-status-ended)
        ctx {:actor (:owner actors) :active-memberships []}]
    (doseq [[label result] [["change-role"
                             (SUT/change-role ended :role-admin ctx now)]
                            ["remove"
                             (SUT/end-membership ended :remove ctx now)]
                            ["leave"
                             (SUT/end-membership ended :leave ctx now)]]]
      (testing (str label " refuses an ended membership with its payload")
        (is (rejected? :membership/invalid-status result))
        (is (= {:membership-id "mem.1"
                :status :membership-status-ended
                :allowed #{:membership-status-active}}
               (dissoc (error/payload result) :message)))
        (is (string? (:message (error/payload result))))))
    (testing "the source state is checked before the grant"
      (is (rejected? :membership/invalid-status
                     (SUT/change-role ended
                                      :role-admin
                                      {:actor (:viewer actors)
                                       :active-memberships []}
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
        {"accept"
         #(SUT/accept-invitation % "usr.new" {:active-memberships []} now)
         "decline" #(SUT/decline-invitation % now)
         "withdraw" #(SUT/withdraw-invitation % now)}]
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
        (let [result (SUT/resend-invitation inv "hash-2" now)]
          (is (rejected? :invitation/invalid-status result))
          (is (= {:invitation-id "inv.1"
                  :status status
                  :allowed #{:invitation-status-pending
                             :invitation-status-expired}}
                 (dissoc (error/payload result) :message)))
          (is (not (mentions? result "hash-1")))
          (is (not (mentions? result "hash-2"))))))
    (testing "accept, decline and withdraw move a pending invitation"
      (is (= {:status :invitation-status-accepted
              :accepted-by-user-id "usr.new"
              :updated-at now}
             (select-keys (SUT/accept-invitation pending
                                                 "usr.new"
                                                 {:active-memberships []}
                                                 now)
                          [:status :accepted-by-user-id :updated-at])))
      (is (= :invitation-status-declined
             (:status (SUT/decline-invitation pending now))))
      (is (= :invitation-status-withdrawn
             (:status (SUT/withdraw-invitation pending now)))))
    (testing "resend renews a pending invitation"
      (let [resent (SUT/resend-invitation pending "hash-2" now)]
        (is (= "hash-2" (:token-hash resent)))
        (is (= (+ now SUT/invitation-lifetime-ms) (:expires-at resent)))))))

(deftest one-active-membership-test
  (let [existing (membership "mem.1" "usr.1" :role-viewer)
        ended (membership "mem.2" "usr.2" :role-viewer :membership-status-ended)
        pending (invitation :invitation-status-pending (+ now day-ms))]
    (testing "an active member of the bank is refused"
      (is (rejected? :membership/already-exists
                     (SUT/check-not-member [existing] "usr.1")))
      (is (rejected? :membership/already-exists
                     (SUT/accept-invitation pending
                                            "usr.1"
                                            {:active-memberships [existing]}
                                            now))))
    (testing "an ended membership does not count"
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
                       (SUT/new-invitation input ctx "hash-2" now)))
        (is (rejected? :invitation/already-member
                       (SUT/new-invitation
                        input
                        (assoc ctx :member-emails ["ada@example.test"])
                        "hash-2"
                        now)))
        (is (not-granted? (SUT/new-invitation (assoc input :role :role-owner)
                                              (assoc ctx :actor (:admin actors))
                                              "hash-2"
                                              now)))))))

(deftest expiry-test
  (let [expires-at (+ now day-ms)
        pending (invitation :invitation-status-pending expires-at)]
    (testing "the lifetime is seven days"
      (is (= (* 7 day-ms) SUT/invitation-lifetime-ms)))
    (testing "a pending invitation reads pending before its expiry"
      (is (= :invitation-status-pending
             (SUT/effective-status pending (dec expires-at)))))
    (testing "a pending invitation reads expired from its expiry on"
      (is (= :invitation-status-expired
             (SUT/effective-status pending expires-at)))
      (is (= :invitation-status-expired
             (SUT/effective-status pending (+ expires-at day-ms)))))
    (testing "an answered invitation keeps its status past expiry"
      (is (= :invitation-status-accepted
             (SUT/effective-status
              (assoc pending :status :invitation-status-accepted)
              (+ expires-at day-ms)))))
    (testing "accept past expiry is refused as expired"
      (let [result (SUT/accept-invitation pending
                                          "usr.new"
                                          {:active-memberships []}
                                          (+ expires-at day-ms))]
        (is (rejected? :invitation/invalid-status result))
        (is (= :invitation-status-expired (:status (error/payload result))))))
    (testing "resend of an expired invitation is allowed and renews it"
      (let [later (+ expires-at (* 2 day-ms))
            resent (SUT/resend-invitation pending "hash-2" later)]
        (is (not (error/anomaly? resent)))
        (is (= :invitation-status-pending (:status resent)))
        (is (= (+ later SUT/invitation-lifetime-ms) (:expires-at resent)))
        (is (= "hash-2" (:token-hash resent)))
        (is (= :invitation-status-pending
               (SUT/effective-status resent later)))))))

(deftest check-recipient-test
  (let [inv (invitation :invitation-status-pending (+ now day-ms))]
    (testing "a matching token hash is proof"
      (is (nil? (SUT/check-recipient inv {:token-hash "hash-1"}))))
    (testing "a verified email matching in any case is proof"
      (is (nil? (SUT/check-recipient inv
                                     {:email "ADA@example.TEST"
                                      :email-verified? true}))))
    (doseq [[label proof] [["a wrong token hash" {:token-hash "hash-9"}]
                           ["an unverified matching email"
                            {:email "ada@example.test" :email-verified? false}]
                           ["a matching email with no verified claim"
                            {:email "ada@example.test"}]
                           ["a verified email of someone else"
                            {:email "bob@example.test" :email-verified? true}]
                           ["no proof at all" {}]]]
      (testing (str label " is not found")
        (let [result (SUT/check-recipient inv proof)]
          (is (rejected? :invitation/not-found result))
          (is (not (mentions? result "hash-1")))
          (is (not (mentions? result "hash-9"))))))))

(deftest constructors-test
  (testing "new-membership builds an active membership at now"
    (let [m (SUT/new-membership {:user-id "usr.1"
                                 :bank-id "bnk.1"
                                 :role :role-developer
                                 :invitation-id "inv.1"}
                                now)]
      (is (re-find #"^mem\." (:membership-id m)))
      (is (= {:user-id "usr.1"
              :bank-id "bnk.1"
              :role :role-developer
              :status :membership-status-active
              :invitation-id "inv.1"
              :created-at now
              :updated-at now}
             (dissoc m :membership-id)))))
  (testing "new-membership defaults to owner and omits a missing invitation"
    (let [m (SUT/new-membership {:user-id "usr.1" :bank-id "bnk.1"} now)]
      (is (= :role-owner (:role m)))
      (is (not (contains? m :invitation-id)))))
  (testing "new-invitation builds a pending invitation for seven days"
    (let [inv (SUT/new-invitation
               {:bank-id "bnk.1"
                :email "Ada@Example.test"
                :role :role-owner
                :reason "Founder handover"}
               {:actor operator-actor :member-emails [] :invitations []}
               "hash-1"
               now)]
      (is (re-find #"^inv\." (:invitation-id inv)))
      (is (= {:bank-id "bnk.1"
              :email "Ada@Example.test"
              :email-lower "ada@example.test"
              :role :role-owner
              :status :invitation-status-pending
              :token-hash "hash-1"
              :expires-at (+ now SUT/invitation-lifetime-ms)
              :invited-by {:kind :actor-kind-operator :principal-id "ops.1"}
              :reason "Founder handover"
              :created-at now
              :updated-at now}
             (dissoc inv :invitation-id)))))
  (testing "new-invitation omits a blank reason and the actor's role"
    (let [inv (SUT/new-invitation {:bank-id "bnk.1"
                                   :email "a@example.test"
                                   :role :role-viewer
                                   :reason ""}
                                  {:actor (:admin actors)}
                                  "hash-1"
                                  now)]
      (is (not (contains? inv :reason)))
      (is (= {:kind :actor-kind-member :principal-id "usr.role-admin"}
             (:invited-by inv)))))
  (testing "change-role sets the role at now"
    (let [m (membership "mem.1" "usr.1" :role-viewer)]
      (is (= (assoc m :role :role-developer :updated-at now)
             (SUT/change-role m
                              :role-developer
                              {:actor (:admin actors) :active-memberships [m]}
                              now)))))
  (testing "end-membership records when and by whom"
    (let [m (membership "mem.1" "usr.1" :role-viewer)]
      (is (= (assoc m
                    :status :membership-status-ended
                    :ended-at now
                    :ended-by {:kind :actor-kind-operator :principal-id "ops.1"}
                    :updated-at now)
             (SUT/end-membership m
                                 :remove
                                 {:actor operator-actor :active-memberships [m]}
                                 now)))))
  (testing "new-access-event keeps the fields that are set"
    (let [event (SUT/new-access-event "bnk.1"
                                      :access-event-kind-role-changed
                                      (:owner actors)
                                      {:subject-user-id "usr.1"
                                       :membership-id "mem.1"
                                       :role-before :role-viewer
                                       :role-after :role-admin
                                       :reason nil
                                       :token "never-stored"}
                                      now)]
      (is (re-find #"^aev\." (:access-event-id event)))
      (is (= {:bank-id "bnk.1"
              :kind :access-event-kind-role-changed
              :actor {:kind :actor-kind-member :principal-id "usr.role-owner"}
              :subject-user-id "usr.1"
              :membership-id "mem.1"
              :role-before :role-viewer
              :role-after :role-admin
              :occurred-at now}
             (dissoc event :access-event-id))))))
