(ns com.repldriven.queenswood.membership.interface-test
  "The access writes against a real record store: what the domain tests
  cannot see because it depends on what a transaction reads. An accept
  run twice writes one membership; a removal ends a membership and a
  re-invitation writes a new one; expiry is read, never written; a
  target outside the actor's reach is not found and nothing is written;
  and two concurrent writes that would each pass alone conflict, so one
  is refused on retry (TS-4, REQ-012).

  The pure rules live in `domain-test`, the records in `store-test`."
  (:require
    [com.repldriven.queenswood.fdb.interface :as fdb]
    [com.repldriven.queenswood.testcontainers.interface]

    [com.repldriven.queenswood.membership.interface :as SUT]

    [com.repldriven.queenswood.user.interface :as user]

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

(def ^:private config-file "classpath:membership/application-test.yml")

(def ^:private day-ms (* 24 60 60 1000))

(defn- member
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

(defn- invite
  [config bank-id actor email role]
  (let [{:keys [token token-hash]} (SUT/new-invitation-token)
        result (SUT/invite config
                           bank-id
                           {:email email :role role}
                           {:actor actor :token-hash token-hash})]
    (if (error/anomaly? result) result (assoc result :token token))))

(defn- token-proof
  [invitation]
  {:token-hash (SUT/token-hash (:token invitation))})

(defn- kinds
  [page]
  (mapv :kind (:access-events page)))

(deftest token-test
  (testing "a token is 43 characters of base64url and its hash hex SHA-256"
    (let [{:keys [token token-hash]} (SUT/new-invitation-token)]
      (is (re-matches #"[A-Za-z0-9_-]{43}" token))
      (is (re-matches #"[0-9a-f]{64}" token-hash))
      (is (= token-hash (SUT/token-hash token)))
      (is (not= token (:token (SUT/new-invitation-token))))))
  (testing "a missing token hashes to nil rather than throwing"
    (is (nil? (SUT/token-hash nil)))))

(deftest accept-run-twice-writes-one-membership-test
  (with-test-system
   [sys config-file]
   (let [config (fdb-config sys)
         bank-id "bnk.accept"
         owner (member "usr.accept.owner" :role-owner)]
     (nom-test> [_ (SUT/new-membership config
                                       {:user-id "usr.accept.owner"
                                        :bank-id bank-id})
                 _ (SUT/record-bank-created config bank-id {:actor operator})
                 invitation (invite config
                                    bank-id
                                    owner
                                    "Invitee@Example.com"
                                    :role-developer)
                 _ (testing "an invitation is pending and carries no token hash"
                     (is (= :invitation-status-pending (:status invitation)))
                     (is (= "invitee@example.com" (:email-lower invitation)))
                     (is (not (contains? invitation :token-hash))))
                 membership (SUT/accept config
                                        (:invitation-id invitation)
                                        (token-proof invitation)
                                        {:user-id "usr.accept.invitee"})
                 _ (testing
                     "accepting writes a membership with the invitation's role"
                     (is (= :membership-status-active (:status membership)))
                     (is (= :role-developer (:role membership)))
                     (is (= (:invitation-id invitation)
                            (:invitation-id membership))))
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
                 members (SUT/list-active-by-bank config bank-id)
                 _ (testing "and the bank holds one membership for the invitee"
                     (is (= 1
                            (count (filter #(= "usr.accept.invitee"
                                               (:user-id %))
                                           members)))))
                 accepted (SUT/find-invitation config
                                               bank-id
                                               (:invitation-id invitation))
                 _ (testing
                     "the invitation reads accepted by the person who accepted"
                     (is (= :invitation-status-accepted (:status accepted)))
                     (is (= "usr.accept.invitee"
                            (:accepted-by-user-id accepted)))
                     (is (not (contains? accepted :token-hash))))
                 by-email
                 (invite config bank-id owner "second@example.com" :role-viewer)
                 _ (testing "an unverified email does not reach an invitation"
                     (let [stranger (SUT/find-invitation-for-recipient
                                     config
                                     (:invitation-id by-email)
                                     {:email "second@example.com"
                                      :email-verified? false})]
                       (is (error/rejection? stranger))
                       (is (refused? :invitation/not-found stranger))))
                 seen (SUT/find-invitation-for-recipient
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
                 history (SUT/list-access-events config bank-id)
                 _ (testing "every write recorded its event, newest first"
                     (is (= [:access-event-kind-invitation-accepted
                             :access-event-kind-invitation-created
                             :access-event-kind-invitation-accepted
                             :access-event-kind-invitation-created
                             :access-event-kind-bank-created]
                            (kinds history)))
                     (is (= {:kind :actor-kind-member
                             :principal-id "usr.accept.invitee"}
                            (:actor (nth (:access-events history) 2))))
                     (is (= "usr.accept.invitee"
                            (:subject-user-id (nth (:access-events history)
                                                   2)))))]))))

(deftest invite-refuses-a-member-and-a-pending-address-test
  (with-test-system
   [sys config-file]
   (let [config (fdb-config sys)
         bank-id "bnk.invite"
         owner (member "usr.invite.owner" :role-owner)]
     (nom-test>
       [existing (user/upsert-by-sub config
                                     {:issuer "https://idp.test"
                                      :sub "invite-member"
                                      :email "Member@Example.com"
                                      :name "Existing Member"
                                      :identity-provider
                                      :identity-provider-password})
        _ (SUT/new-membership config
                              {:user-id "usr.invite.owner" :bank-id bank-id})
        _ (SUT/new-membership
           config
           {:user-id (:user-id existing) :bank-id bank-id :role :role-viewer})
        _ (testing "an address an active member holds is refused"
            (is
             (refused?
              :invitation/already-member
              (invite config bank-id owner "member@example.com" :role-admin))))
        first-invitation
        (invite config bank-id owner "new@example.com" :role-admin)
        _ (testing "a second pending invitation to one address is refused"
            (let [result
                  (invite config bank-id owner "NEW@example.com" :role-viewer)]
              (is (refused? :invitation/already-exists result))
              (is (not (mentions? result (:token first-invitation))))
              (is (not (mentions? result
                                  (SUT/token-hash (:token
                                                   first-invitation)))))))
        _ (testing "an admin inviting an owner is not granted"
            (let [result (invite config
                                 bank-id
                                 (member "usr.invite.admin" :role-admin)
                                 "owner@example.com"
                                 :role-owner)]
              (is (error/unauthorized? result))
              (is (refused? :membership/role-not-granted result))))
        _ (testing "an operator's invitation needs a reason"
            (is (refused? :invitation/reason-required
                          (invite config
                                  bank-id
                                  operator
                                  "handover@example.com"
                                  :role-owner))))
        {:keys [token-hash]} (SUT/new-invitation-token)
        granted (SUT/invite
                 config
                 bank-id
                 {:email "handover@example.com" :role :role-owner}
                 {:actor operator :token-hash token-hash :reason "Locked out"})
        _ (testing "and records the reason when given one"
            (is (= "Locked out" (:reason granted))))
        _
        (testing "an admin may neither withdraw nor resend an owner invitation"
          (let [admin (member "usr.invite.admin" :role-admin)
                withdrawn (SUT/withdraw config
                                        bank-id
                                        (:invitation-id granted)
                                        {:actor admin})
                resent (SUT/resend config
                                   bank-id
                                   (:invitation-id granted)
                                   {:actor admin
                                    :token-hash (:token-hash
                                                 (SUT/new-invitation-token))})]
            (is (refused? :membership/role-not-granted withdrawn))
            (is (refused? :membership/role-not-granted resent))))
        still (SUT/find-invitation config bank-id (:invitation-id granted))
        _ (testing "and the owner invitation is untouched"
            (is (= :invitation-status-pending (:status still))))
        listed (SUT/list-invitations-by-bank config bank-id)
        _ (testing "the bank lists its two pending invitations"
            (is (= #{"new@example.com" "handover@example.com"}
                   (set (map :email listed))))
            (is (not-any? #(contains? % :token-hash) listed)))]))))

(deftest removal-ends-and-reinvitation-writes-a-new-membership-test
  (with-test-system
   [sys config-file]
   (let [config (fdb-config sys)
         bank-id "bnk.removal"
         owner (member "usr.removal.owner" :role-owner)]
     (nom-test> [_ (SUT/new-membership config
                                       {:user-id "usr.removal.owner"
                                        :bank-id bank-id})
                 invitation
                 (invite config bank-id owner "removed@example.com" :role-admin)
                 joined (SUT/accept config
                                    (:invitation-id invitation)
                                    (token-proof invitation)
                                    {:user-id "usr.removal.member"})
                 promoted (SUT/change-role config
                                           bank-id
                                           (:membership-id joined)
                                           :role-developer
                                           {:actor owner
                                            :reason "Fewer rights"})
                 _ (testing "a role change writes the new role"
                     (is (= :role-developer (:role promoted))))
                 ended (SUT/remove-member config
                                          bank-id
                                          (:membership-id joined)
                                          {:actor owner
                                           :reason "Left the company"})
                 loaded (SUT/find-by-id config (:membership-id joined))
                 _ (testing "a removed membership reads ended, by whom and when"
                     (is (= :membership-status-ended (:status ended)))
                     (is (= :membership-status-ended (:status loaded)))
                     (is (pos-int? (:ended-at loaded)))
                     (is (= {:kind :actor-kind-member
                             :principal-id "usr.removal.owner"}
                            (:ended-by loaded))))
                 active (SUT/list-active-by-user config "usr.removal.member")
                 _ (testing "and is no longer active" (is (= [] active)))
                 _ (testing
                     "a second removal is refused on the membership's status"
                     (is (refused? :membership/invalid-status
                                   (SUT/remove-member config
                                                      bank-id
                                                      (:membership-id joined)
                                                      {:actor owner}))))
                 again (invite config
                               bank-id
                               owner
                               "removed@example.com"
                               :role-viewer)
                 rejoined (SUT/accept config
                                      (:invitation-id again)
                                      (token-proof again)
                                      {:user-id "usr.removal.member"})
                 listed (SUT/list-by-user config "usr.removal.member")
                 _
                 (testing
                   "re-invited and accepting, the person holds a new membership"
                   (is (not= (:membership-id joined) (:membership-id rejoined)))
                   (is (= #{[(:membership-id joined) :membership-status-ended]
                            [(:membership-id rejoined)
                             :membership-status-active]}
                          (set (map (juxt :membership-id :status) listed)))))
                 history (SUT/list-access-events config bank-id)
                 change (first (filter #(= :access-event-kind-role-changed
                                           (:kind %))
                                       (:access-events history)))
                 removal (first (filter #(= :access-event-kind-member-removed
                                            (:kind %))
                                        (:access-events history)))
                 _ (testing
                     "the history names roles before and after, and the reason"
                     (is (= [:role-admin :role-developer "Fewer rights"]
                            ((juxt :role-before :role-after :reason) change)))
                     (is (= [:role-developer "Left the company"
                             "usr.removal.member"]
                            ((juxt :role-before :reason :subject-user-id)
                             removal))))
                 _ (testing "the last owner may not leave"
                     (let [owner-membership (first (SUT/list-active-by-user
                                                    config
                                                    "usr.removal.owner"))]
                       (is (refused? :membership/last-owner
                                     (SUT/leave
                                      config
                                      (:membership-id owner-membership)
                                      {:user-id "usr.removal.owner"})))))]))))

(deftest expiry-is-read-and-never-written-test
  (with-test-system
   [sys config-file]
   (let [config (fdb-config sys)
         bank-id "bnk.expiry"
         owner (member "usr.expiry.owner" :role-owner)
         created-at 1700000000000
         later (+ created-at (* 8 day-ms))
         {:keys [token token-hash]} (SUT/new-invitation-token)
         proof {:token-hash token-hash}]
     (nom-test> [invitation
                 (SUT/invite
                  config
                  bank-id
                  {:email "late@example.com" :role :role-viewer}
                  {:actor owner :token-hash token-hash :now created-at})
                 id (:invitation-id invitation)
                 _ (testing
                     "an invitation expires seven days after it is created"
                     (is (= (+ created-at (* 7 day-ms))
                            (:expires-at invitation))))
                 expired (SUT/find-invitation config bank-id id {:now later})
                 by-bank
                 (SUT/list-invitations-by-bank config bank-id {:now later})
                 by-recipient (SUT/find-invitation-for-recipient config
                                                                 id
                                                                 proof
                                                                 {:now later})
                 pending (SUT/list-pending-invitations-by-email
                          config
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
                 stored
                 (SUT/find-invitation config bank-id id {:now created-at})
                 _ (testing "while the row itself is still pending"
                     (is (= :invitation-status-pending (:status stored))))
                 fresh (SUT/new-invitation-token)
                 resent (SUT/resend config
                                    bank-id
                                    id
                                    {:actor owner
                                     :token-hash (:token-hash fresh)
                                     :now later})
                 _ (testing "resend succeeds with a later expires-at"
                     (is (= :invitation-status-pending (:status resent)))
                     (is (= (+ later (* 7 day-ms)) (:expires-at resent))))
                 _ (testing "and the previous token no longer reaches it"
                     (is (refused? :invitation/not-found
                                   (SUT/find-invitation-for-recipient
                                    config
                                    id
                                    proof
                                    {:now later}))))
                 membership (SUT/accept config
                                        id
                                        {:token-hash (:token-hash fresh)}
                                        {:user-id "usr.expiry.late"
                                         :now (inc later)})
                 _ (testing "while the fresh one accepts"
                     (is (= :role-viewer (:role membership))))]))))

(deftest targets-out-of-reach-are-not-found-test
  (with-test-system
   [sys config-file]
   (let [config (fdb-config sys)
         owner-a (member "usr.reach.owner-a" :role-owner)]
     (nom-test> [_ (SUT/new-membership config
                                       {:user-id "usr.reach.owner-a"
                                        :bank-id "bnk.reach.a"})
                 target (SUT/new-membership config
                                            {:user-id "usr.reach.member-b"
                                             :bank-id "bnk.reach.b"
                                             :role :role-developer})
                 _
                 (testing
                   "another bank's membership is not found to change or remove"
                   (let [changed (SUT/change-role config
                                                  "bnk.reach.a" (:membership-id
                                                                 target)
                                                  :role-viewer {:actor owner-a})
                         removed (SUT/remove-member config
                                                    "bnk.reach.a"
                                                    (:membership-id target)
                                                    {:actor owner-a})]
                     (is (refused? :membership/not-found changed))
                     (is (refused? :membership/not-found removed))))
                 _ (testing "another person's membership is not found to leave"
                     (is (refused? :membership/not-found
                                   (SUT/leave config
                                              (:membership-id target)
                                              {:user-id "usr.reach.owner-a"}))))
                 _ (testing "an unknown membership is not found"
                     (is (refused? :membership/not-found
                                   (SUT/change-role config
                                                    "bnk.reach.a" "mem.unknown"
                                                    :role-viewer {:actor
                                                                  owner-a}))))
                 loaded (SUT/find-by-id config (:membership-id target))
                 history-a (SUT/list-access-events config "bnk.reach.a")
                 history-b (SUT/list-access-events config "bnk.reach.b")
                 _ (testing "and nothing is written"
                     (is (= target loaded))
                     (is (= [] (:access-events history-a)))
                     (is (= [] (:access-events history-b))))]))))

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
     (nom-test> [left (SUT/new-membership config
                                          {:user-id "usr.race.left"
                                           :bank-id bank-id})
                 right (SUT/new-membership config
                                           {:user-id "usr.race.right"
                                            :bank-id bank-id})
                 results (let [gate (CountDownLatch. 2)
                               demote (fn [actor-id target]
                                        (latched config
                                                 gate
                                                 #(SUT/change-role
                                                   %
                                                   bank-id
                                                   (:membership-id target)
                                                   :role-admin
                                                   {:actor (member
                                                            actor-id
                                                            :role-owner)})))
                               a (demote "usr.race.left" right)
                               b (demote "usr.race.right" left)]
                           [@a @b])
                 _ (testing "exactly one demotion commits"
                     (is (= 1 (count (remove error/anomaly? results))))
                     (is (= [:membership/last-owner]
                            (map error/kind (filter error/anomaly? results)))))
                 members (SUT/list-active-by-bank config bank-id)
                 history (SUT/list-access-events config bank-id)
                 _
                 (testing "and the bank keeps one owner and one event"
                   (is (= [:role-admin :role-owner] (sort (map :role members))))
                   (is (= [:access-event-kind-role-changed] (kinds history))))]))))

(deftest concurrent-accepts-by-one-person-write-one-membership-test
  (with-test-system
   [sys config-file]
   (let [config (fdb-config sys)
         bank-id "bnk.race.accepts"
         owner (member "usr.race.owner" :role-owner)]
     (nom-test> [_ (SUT/new-membership config
                                       {:user-id "usr.race.owner"
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
                     (is (= [:membership/already-exists]
                            (map error/kind (filter error/anomaly? results)))))
                 held (SUT/list-active-by-user config "usr.race.one")
                 _ (testing "and the person holds one membership of the bank"
                     (is (= 1 (count held))))]))))
