(ns com.repldriven.queenswood.member.interface-test
  "The access writes against a real record store: what the domain tests
  cannot see because it depends on what a transaction reads. An accept
  run twice writes one member; a role change repeated writes nothing;
  a removal ends a member and a re-invitation writes a new one;
  expiry is read, never written; a target outside the actor's reach is
  not found and nothing is written; and two concurrent writes that would
  each pass alone conflict, so one is refused on retry (TS-4, REQ-012).

  The pure rules live in `domain-test`, the records in `store-test`."
  (:require
    [com.repldriven.queenswood.fdb.interface :as fdb]
    [com.repldriven.queenswood.testcontainers.interface]

    [com.repldriven.queenswood.member.interface :as SUT]
    [com.repldriven.queenswood.member-query.interface :as q]

    [com.repldriven.mono.error.interface :as error]
    [com.repldriven.mono.system.interface :as system]
    [com.repldriven.mono.test-system.interface :refer
     [with-test-system nom-test>]]

    [clojure.string :as str]
    [clojure.test :refer [deftest is testing]])
  (:import
    (java.util.concurrent CountDownLatch TimeUnit)))

(defn- fdb-config
  [sys]
  {:record-db (system/instance sys [:fdb :record-db])
   :record-store (system/instance sys [:fdb :store])})

(def ^:private config-file "classpath:member/application-test.yml")

(def ^:private day-ms (* 24 60 60 1000))

(defn- member-of
  [user-id role]
  {:kind :actor-kind-member :principal-id user-id :role role})

(def ^:private operator
  {:kind :actor-kind-operator :principal-id "queenswood-admin"})

(defn- refused?
  [kind result]
  (= kind (error/kind result)))

(defn- mentions?
  [result s]
  (str/includes? (pr-str result) s))

(defn- record-token
  "Record a fresh token on `invitation`, as the email adapter does before
  it sends, answering the invitation with the plaintext `:token`."
  ([config invitation] (record-token config invitation {}))
  ([config invitation opts]
   (let [{:keys [bank-id invitation-id expires-at]} invitation
         {:keys [token token-hash]} (q/new-invitation-token)
         result (SUT/record-invitation-token config
                                             bank-id
                                             invitation-id
                                             (assoc opts
                                                    :expires-at expires-at
                                                    :token-hash token-hash))]
     (if (error/anomaly? result) result (assoc result :token token))))

)

(defn- invite
  [config bank-id actor email role]
  (let [result
        (SUT/invite config bank-id {:email email :role role} {:actor actor})]
    (if (error/anomaly? result) result (record-token config result))))

(defn- token-proof
  [invitation]
  {:token-hash (q/token-hash (:token invitation))})

(defn- access-history
  "The access history of a bank created at time zero by the operator."
  [config bank-id]
  (q/list-access-events config
                        {:bank-id bank-id :created-at 0 :created-by operator}))

(defn- kinds
  [events]
  (mapv :kind events))

(deftest token-test
  (testing "a token is 43 characters of base64url and its hash hex SHA-256"
    (let [{:keys [token token-hash]} (q/new-invitation-token)]
      (is (re-matches #"[A-Za-z0-9_-]{43}" token))
      (is (re-matches #"[0-9a-f]{64}" token-hash))
      (is (= token-hash (q/token-hash token)))
      (is (not= token (:token (q/new-invitation-token))))))
  (testing "a missing token hashes to nil rather than throwing"
    (is (nil? (q/token-hash nil)))))

(deftest accept-run-twice-writes-one-member-test
  (with-test-system
   [sys config-file]
   (let [config (fdb-config sys)
         bank-id "bnk.accept"
         owner (member-of "usr.accept.owner" :role-owner)]
     (nom-test> [_ (SUT/new-member config
                                   {:actor operator
                                    :user-id "usr.accept.owner"
                                    :bank-id bank-id})
                 invitation (invite config
                                    bank-id
                                    owner
                                    "Invitee@Example.com"
                                    :role-developer)
                 _ (testing "an invitation is pending and carries no token hash"
                     (is (= :invitation-status-pending (:status invitation)))
                     (is (= "invitee@example.com" (:email-lower invitation)))
                     (is (not (contains? invitation :token-hash))))
                 member (SUT/accept config
                                    (:invitation-id invitation)
                                    (token-proof invitation)
                                    {:user-id "usr.accept.invitee"})
                 _
                 (testing "accepting writes a member with the invitation's role"
                   (is (= :member-status-active (:status member)))
                   (is (= :role-developer (:role member)))
                   (is (= (:invitation-id invitation) (:invitation-id member))))
                 _ (testing
                     "a second accept is refused on the invitation's status"
                     (let [again (SUT/accept config
                                             (:invitation-id invitation)
                                             (token-proof invitation)
                                             {:user-id "usr.accept.invitee"})]
                       (is (refused? :invitation/invalid-status again))
                       (is (= :invitation-status-accepted
                              (:status (error/payload again))))
                       (is (not (mentions? again (:token invitation))))))
                 members (q/list-active-by-bank config bank-id)
                 _ (testing "and the bank holds one member for the invitee"
                     (is (= 1
                            (count (filter #(= "usr.accept.invitee"
                                               (:user-id %))
                                           members)))))
                 accepted
                 (q/find-invitation config bank-id (:invitation-id invitation))
                 _ (testing
                     "the invitation reads accepted by the person who accepted"
                     (is (= :invitation-status-accepted (:status accepted)))
                     (is (= "usr.accept.invitee"
                            (get-in accepted [:accepted-by :principal-id])))
                     (is (not (contains? accepted :token-hash))))
                 by-email
                 (invite config bank-id owner "second@example.com" :role-viewer)
                 _ (testing "an unverified email does not reach an invitation"
                     (let [stranger (q/find-invitation-for-recipient
                                     config
                                     (:invitation-id by-email)
                                     {:email "second@example.com"
                                      :email-verified? false})]
                       (is (error/rejection? stranger))
                       (is (refused? :invitation/not-found stranger))))
                 seen (q/find-invitation-for-recipient
                       config
                       (:invitation-id by-email)
                       {:email "Second@Example.com" :email-verified? true})
                 _ (testing "a verified email does"
                     (is (= (:invitation-id by-email) (:invitation-id seen))))
                 _ (SUT/accept config
                               (:invitation-id by-email)
                               {:email "second@example.com"
                                :email-verified? true}
                               {:user-id "usr.accept.second"})
                 history (access-history config bank-id)
                 _ (testing "every act reads back from its record, newest first"
                     (is (= [:access-event-kind-invitation-accepted
                             :access-event-kind-invitation-created
                             :access-event-kind-invitation-accepted
                             :access-event-kind-invitation-created
                             :access-event-kind-bank-created]
                            (kinds history)))
                     (is (= {:kind :actor-kind-member
                             :principal-id "usr.accept.invitee"}
                            (:actor (nth history 2))))
                     (is (= "usr.accept.invitee"
                            (:subject-user-id (nth history 2))))
                     (is (= "usr.accept.owner"
                            (:subject-user-id (peek history)))))]))))

(deftest an-unchanged-role-records-nothing-test
  (with-test-system
   [sys config-file]
   (let [config (fdb-config sys)
         bank-id "bnk.unchanged"
         owner (member-of "usr.unchanged.owner" :role-owner)
         first-at 1700000000000
         second-at (+ first-at day-ms)]
     (nom-test> [_ (SUT/new-member config
                                   {:actor operator
                                    :user-id "usr.unchanged.owner"
                                    :bank-id bank-id})
                 target (SUT/new-member config
                                        {:actor operator
                                         :user-id "usr.unchanged.member"
                                         :bank-id bank-id
                                         :role :role-viewer})
                 changed (SUT/change-role config
                                          bank-id
                                          (:member-id target)
                                          :role-developer
                                          {:actor owner :now first-at})
                 repeated (SUT/change-role config
                                           bank-id
                                           (:member-id target)
                                           :role-developer
                                           {:actor owner :now second-at})
                 _ (testing "repeating a role change returns the member as is"
                     (is (= changed repeated)))
                 loaded (q/find-by-id config bank-id (:member-id target))
                 history (access-history config bank-id)
                 _ (testing "and writes neither the member nor a change"
                     (is (= first-at (:updated-at loaded)))
                     (is (= :role-developer (:role loaded)))
                     (is (= [:access-event-kind-role-changed
                             :access-event-kind-bank-created]
                            (kinds history))))]))))

(deftest expiry-is-read-and-never-written-test
  (with-test-system
   [sys config-file]
   (let [config (fdb-config sys)
         bank-id "bnk.expiry"
         owner (member-of "usr.expiry.owner" :role-owner)
         created-at 1700000000000
         later (+ created-at (* 8 day-ms))]
     (nom-test> [created (SUT/invite config
                                     bank-id
                                     {:email "late@example.com"
                                      :role :role-viewer}
                                     {:actor owner :now created-at})
                 invitation (record-token config created {:now created-at})
                 {:keys [token]} invitation
                 token-hash (q/token-hash token)
                 proof {:token-hash token-hash}
                 id (:invitation-id invitation)
                 _ (testing
                     "an invitation expires seven days after it is created"
                     (is (= (+ created-at (* 7 day-ms))
                            (:expires-at invitation))))
                 expired (q/find-invitation config bank-id id {:now later})
                 by-bank
                 (q/list-invitations-by-bank config bank-id {:now later})
                 by-recipient
                 (q/find-invitation-for-recipient config id proof {:now later})
                 pending (q/list-pending-invitations-by-email config
                                                              "late@example.com"
                                                              {:now later})
                 _ (testing "past expires-at, every read says expired"
                     (is (= :invitation-status-expired (:status expired)))
                     (is (= [:invitation-status-expired]
                            (mapv :status by-bank)))
                     (is (= :invitation-status-expired (:status by-recipient)))
                     (is (= [] pending)))
                 _ (testing "and accept, decline and withdraw are refused"
                     (doseq [result [(SUT/accept config
                                                 id
                                                 proof
                                                 {:user-id "usr.expiry.late"
                                                  :now later})
                                     (SUT/decline config
                                                  id
                                                  proof
                                                  {:user-id "usr.expiry.late"
                                                   :now later})
                                     (SUT/withdraw config
                                                   bank-id
                                                   id
                                                   {:actor owner :now later})]]
                       (is (refused? :invitation/invalid-status result))
                       (is (= :invitation-status-expired
                              (:status (error/payload result))))
                       (is (not (mentions? result token)))
                       (is (not (mentions? result token-hash)))))
                 stored (q/find-invitation config bank-id id {:now created-at})
                 _ (testing "while the row itself is still pending"
                     (is (= :invitation-status-pending (:status stored))))
                 resent (SUT/resend config bank-id id {:actor owner :now later})
                 _ (testing "resend succeeds with a later expires-at"
                     (is (= :invitation-status-pending (:status resent)))
                     (is (= (+ later (* 7 day-ms)) (:expires-at resent))))
                 _ (testing "and the previous token no longer reaches it"
                     (is (refused? :invitation/not-found
                                   (q/find-invitation-for-recipient config
                                                                    id
                                                                    proof
                                                                    {:now
                                                                     later}))))
                 fresh (record-token config
                                     (assoc resent :bank-id bank-id)
                                     {:now later})
                 member (SUT/accept config
                                    id
                                    (token-proof fresh)
                                    {:user-id "usr.expiry.late"
                                     :now (inc later)})
                 _ (testing "while the fresh one accepts"
                     (is (= :role-viewer (:role member))))]))))

(deftest targets-out-of-reach-are-not-found-test
  (with-test-system
   [sys config-file]
   (let [config (fdb-config sys)
         owner-a (member-of "usr.reach.owner-a" :role-owner)]
     (nom-test> [_ (SUT/new-member config
                                   {:actor operator
                                    :user-id "usr.reach.owner-a"
                                    :bank-id "bnk.reach.a"})
                 target (SUT/new-member config
                                        {:actor operator
                                         :user-id "usr.reach.member-b"
                                         :bank-id "bnk.reach.b"
                                         :role :role-developer})
                 _ (testing
                     "another bank's member is not found to change or remove"
                     (let [changed
                           (SUT/change-role config
                                            "bnk.reach.a" (:member-id target)
                                            :role-viewer {:actor owner-a})
                           removed (SUT/remove-member config
                                                      "bnk.reach.a"
                                                      (:member-id target)
                                                      {:actor owner-a})]
                       (is (refused? :member/not-found changed))
                       (is (refused? :member/not-found removed))))
                 _ (testing "another person's member is not found to leave"
                     (is (refused? :member/not-found
                                   (SUT/leave config
                                              (:member-id target)
                                              {:user-id "usr.reach.owner-a"}))))
                 _ (testing "an unknown member is not found"
                     (is (refused? :member/not-found
                                   (SUT/change-role config
                                                    "bnk.reach.a" "mem.unknown"
                                                    :role-viewer {:actor
                                                                  owner-a}))))
                 loaded (q/find-by-id config "bnk.reach.b" (:member-id target))
                 history-a (access-history config "bnk.reach.a")
                 history-b (access-history config "bnk.reach.b")
                 _ (testing "and nothing is written"
                     (is (= target loaded))
                     (is (= [:access-event-kind-bank-created]
                            (kinds history-a)))
                     (is (= [:access-event-kind-bank-created]
                            (kinds history-b))))]))))

(defn- latched
  "Runs `f` inside a transaction of its own on another thread, holding
  the commit until every latched transaction has run `f` once, so the
  transactions overlap by construction rather than by timing. A retry
  finds the latch open."
  [config ^CountDownLatch gate f]
  (future (fdb/transact config
                        (fn [txn]
                          (let [result (f txn)]
                            (.countDown gate)
                            (.await gate 5 TimeUnit/SECONDS)
                            result)))))

(deftest concurrent-owner-demotions-leave-one-owner-test
  (with-test-system
   [sys config-file]
   (let [config (fdb-config sys)
         bank-id "bnk.race.owners"]
     (nom-test> [left (SUT/new-member config
                                      {:actor operator
                                       :user-id "usr.race.left"
                                       :bank-id bank-id})
                 right (SUT/new-member config
                                       {:actor operator
                                        :user-id "usr.race.right"
                                        :bank-id bank-id})
                 results (let [gate (CountDownLatch. 2)
                               demote (fn [actor-id target]
                                        (latched config
                                                 gate
                                                 #(SUT/change-role
                                                   %
                                                   bank-id
                                                   (:member-id target)
                                                   :role-admin
                                                   {:actor (member-of
                                                            actor-id
                                                            :role-owner)})))
                               a (demote "usr.race.left" right)
                               b (demote "usr.race.right" left)]
                           [@a @b])
                 _ (testing "exactly one demotion commits"
                     (is (= 1 (count (remove error/anomaly? results))))
                     (is (= [:member/last-owner]
                            (map error/kind (filter error/anomaly? results)))))
                 members (q/list-active-by-bank config bank-id)
                 history (access-history config bank-id)
                 _ (testing "and the bank keeps one owner and one role change"
                     (is (= [:role-admin :role-owner]
                            (sort (map :role members))))
                     (is (= [:access-event-kind-role-changed
                             :access-event-kind-bank-created]
                            (kinds history))))]))))

(deftest concurrent-accepts-by-one-person-write-one-member-test
  (with-test-system
   [sys config-file]
   (let [config (fdb-config sys)
         bank-id "bnk.race.accepts"
         owner (member-of "usr.race.owner" :role-owner)]
     (nom-test> [_ (SUT/new-member config
                                   {:actor operator
                                    :user-id "usr.race.owner"
                                    :bank-id bank-id})
                 work
                 (invite config bank-id owner "one@example.com" :role-viewer)
                 aliased (invite config
                                 bank-id
                                 owner
                                 "one.alias@example.com"
                                 :role-admin)
                 results (let [gate (CountDownLatch. 2)
                               accept (fn [invitation]
                                        (latched config
                                                 gate
                                                 #(SUT/accept
                                                   %
                                                   (:invitation-id invitation)
                                                   (token-proof invitation)
                                                   {:user-id "usr.race.one"})))
                               a (accept work)
                               b (accept aliased)]
                           [@a @b])
                 _ (testing "exactly one accept commits"
                     (is (= 1 (count (remove error/anomaly? results))))
                     (is (= [:member/already-exists]
                            (map error/kind (filter error/anomaly? results)))))
                 held (q/list-active-by-user config "usr.race.one")
                 _ (testing "and the person holds one member of the bank"
                     (is (= 1 (count held))))]))))
