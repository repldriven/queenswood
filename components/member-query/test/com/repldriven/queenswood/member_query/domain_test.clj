(ns com.repldriven.queenswood.member-query.domain-test
  (:require
    [com.repldriven.queenswood.member-query.domain :as SUT]

    [com.repldriven.mono.error.interface :as error]

    [clojure.test :refer [deftest is testing]]))

(def ^:private now 1789000000000)

(def ^:private day-ms (* 24 60 60 1000))

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

(defn- rejected?
  [kind result]
  (and (error/rejection? result) (= kind (error/kind result))))

(defn- mentions?
  [x value]
  (boolean (some #{value} (tree-seq coll? seq x))))

(deftest effective-status-test
  (let [expires-at (+ now day-ms)
        pending (invitation :invitation-status-pending expires-at)]
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
              (+ expires-at day-ms)))))))

(deftest ensure-found-test
  (let [m {:member-id "mem.1" :user-id "usr.1" :bank-id "bnk.1"}]
    (testing "a loaded member is returned"
      (is (= m (SUT/ensure-found m "mem.1"))))
    (testing "no member is not found"
      (let [result (SUT/ensure-found nil "mem.9")]
        (is (rejected? :member/not-found result))
        (is (= "mem.9" (:member-id (error/payload result))))))))

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
          (is (not (mentions? result "hash-9"))))))
    (testing "an invitation no email has been sent for matches no hash"
      (is (rejected? :invitation/not-found
                     (SUT/check-recipient (assoc inv :token-hash "inv.1")
                                          {:token-hash "hash-1"}))))))

(def ^:private operator {:kind :actor-kind-operator :principal-id "ops.1"})

(def ^:private founder {:kind :actor-kind-member :principal-id "usr.founder"})

(def ^:private joiner {:kind :actor-kind-member :principal-id "usr.joiner"})

(def ^:private bank
  {:bank-id "bnk.01j00000000000000000000000"
   :created-at 1000
   :created-by operator})

(def ^:private founding
  {:bank-id "bnk.01j00000000000000000000000"
   :member-id "mem.01j00000000000000000000001"
   :status :member-status-active
   :role :role-admin
   :user-id "usr.founder"
   :created-at 1000})

(def ^:private joining
  {:bank-id "bnk.01j00000000000000000000000"
   :member-id "mem.01j00000000000000000000002"
   :status :member-status-removed
   :role :role-developer
   :user-id "usr.joiner"
   :invitation-id "inv.01j00000000000000000000003"
   :removed-at 5000
   :removed-by founder
   :removed-reason "Left the company"
   :created-at 3000})

(def ^:private accepted
  {:bank-id "bnk.01j00000000000000000000000"
   :invitation-id "inv.01j00000000000000000000003"
   :status :invitation-status-accepted
   :role :role-developer
   :email "joiner@example.test"
   :reason "Joining the team"
   :created-at 2000
   :created-by founder
   :accepted-at 3000
   :accepted-by joiner})

(def ^:private demotion
  {:bank-id "bnk.01j00000000000000000000000"
   :member-id "mem.01j00000000000000000000001"
   :role-change-id "rch.01j00000000000000000000004"
   :role-before :role-owner
   :role-after :role-admin
   :created-at 4000
   :created-by operator})

(deftest audit-events-test
  (let [events (SUT/audit-events bank [founding joining] [accepted] [demotion])]
    (testing "every act reads back from its record, newest first"
      (is (= [:audit-event-kind-member-removed
              :audit-event-kind-role-changed
              :audit-event-kind-invitation-accepted
              :audit-event-kind-invitation-created
              :audit-event-kind-bank-created]
             (mapv :kind events))))
    (testing "each id is an audit event id, sorting as the events happened"
      (is (every? #(re-matches #"aev\.[0-9a-hjkmnp-tv-z]{26}" %)
                  (map :audit-event-id events)))
      (is (= (map :audit-event-id events)
             (sort #(compare %2 %1) (map :audit-event-id events)))))
    (testing "the bank's creation names its first owner at the role it began"
      (is (= {:actor operator
              :subject-user-id "usr.founder"
              :member-id "mem.01j00000000000000000000001"
              :role-after :role-owner}
             (select-keys (peek events)
                          [:actor :subject-user-id :member-id
                           :role-after]))))
    (testing "an acceptance names the person and the member it made"
      (is (= {:actor joiner
              :subject-user-id "usr.joiner"
              :member-id "mem.01j00000000000000000000002"
              :email "joiner@example.test"
              :role-after :role-developer}
             (select-keys (nth events 2)
                          [:actor :subject-user-id :member-id :email
                           :role-after]))))
    (testing "a removal names the member, their role and the reason"
      (is (= {:actor founder
              :subject-user-id "usr.joiner"
              :role-before :role-developer
              :reason "Left the company"
              :occurred-at 5000}
             (select-keys (first events)
                          [:actor :subject-user-id :role-before :reason
                           :occurred-at])))))
  (testing "acts at the same instant read in the order they happen"
    (let [events
          (SUT/audit-events (assoc bank :created-at 2000) [] [accepted] [])]
      (is (= [:audit-event-kind-invitation-accepted
              :audit-event-kind-invitation-created
              :audit-event-kind-bank-created]
             (mapv :kind events))))))
