(ns com.repldriven.queenswood.membership.store-test
  "The three access records against a real record store (AC-02): a
  membership is found through its bank and its user and no other, an
  invitation and a role change round-trip, every invitation index
  answers, a second invitation under a taken token hash is refused, and
  a role change reads back as its own bank's history. An
  invitation saved under an event name co-commits one changelog entry,
  and one saved without writes none. Reads go through
  `membership-query`.

  The transactions live in `interface-test`; the pure rules in
  `domain-test`."
  (:require
    [com.repldriven.queenswood.fdb.interface :as fdb]
    [com.repldriven.queenswood.testcontainers.interface]

    [com.repldriven.queenswood.membership.store :as SUT]
    [com.repldriven.queenswood.membership-query.interface :as q]
    [com.repldriven.queenswood.schema.interface :as schema]
    [com.repldriven.mono.error.interface :as error]

    [com.repldriven.mono.system.interface :as system]
    [com.repldriven.mono.test-system.interface :refer
     [with-test-system nom-test>]]

    [clojure.string :as str]
    [clojure.test :refer [deftest is testing]]))

(defn- fdb-config
  [sys]
  {:record-db (system/instance sys [:fdb :record-db])
   :record-store (system/instance sys [:fdb :store])})

(def ^:private config-file "classpath:membership/application-test.yml")

(def ^:private created-at 1700000000000)

(def ^:private owner-actor {:kind :actor-kind-member :principal-id "usr.owner"})

(defn- invitation
  [bank-id invitation-id email status token-hash]
  {:invitation-id invitation-id
   :bank-id bank-id
   :email email
   :email-lower (str/lower-case email)
   :role :role-developer
   :status status
   :token-hash token-hash
   :expires-at 1700604800000
   :created-at created-at
   :created-by owner-actor
   :updated-at created-at})

(def ^:private accepter {:kind :actor-kind-member :principal-id "usr.accepted"})

(deftest membership-is-scoped-by-its-bank-and-its-user-test
  (with-test-system
   [sys config-file]
   (let [config (fdb-config sys)
         bank-id "bnk.store.scoped"
         membership {:bank-id bank-id
                     :membership-id "mem.scoped"
                     :status :membership-status-active
                     :role :role-admin
                     :user-id "usr.scoped"
                     :created-at created-at
                     :created-by owner-actor
                     :updated-at created-at}]
     (nom-test> [_ (SUT/save-membership config membership)
                 loaded (q/find-by-id config bank-id "mem.scoped")
                 _ (testing "a membership round-trips through its bank"
                     (is (= membership loaded)))
                 _ (testing "and is not found through another bank"
                     (is (= :membership/not-found
                            (error/kind (q/find-by-id config
                                                      "bnk.store.other"
                                                      "mem.scoped")))))
                 own (q/find-user-membership config "usr.scoped" "mem.scoped")
                 _ (testing "its user finds it by id" (is (= membership own)))
                 _ (testing "and another user does not"
                     (is (= :membership/not-found
                            (error/kind (q/find-user-membership
                                         config
                                         "usr.other"
                                         "mem.scoped")))))
                 _ (SUT/save-membership
                    config
                    (assoc membership
                           :status :membership-status-removed
                           :ended-at 1700000001000
                           :ended-by owner-actor
                           :ended-reason "Left the company"))
                 ended (q/find-by-id config bank-id "mem.scoped")
                 _ (testing "an ended membership keeps how, by whom and why"
                     (is (= :membership-status-removed (:status ended)))
                     (is (= owner-actor (:ended-by ended)))
                     (is (= "Left the company" (:ended-reason ended))))
                 active (q/list-active-by-bank config bank-id)
                 listed (q/list-by-bank config bank-id)
                 by-user (q/list-active-by-user config "usr.scoped")
                 _ (testing "an ended membership is listed but not active"
                     (is (= [] active))
                     (is (= [] by-user))
                     (is (= ["mem.scoped"] (mapv :membership-id listed))))]))))

(deftest invitation-round-trips-and-every-index-answers-test
  (with-test-system
   [sys config-file]
   (let [config (fdb-config sys)
         bank-id "bnk.store.invitations"
         pending (invitation bank-id
                             "inv.store.1" "Invitee@Example.com"
                             :invitation-status-pending "hash-1")]
     (nom-test> [_ (SUT/save-invitation config
                                        (assoc pending
                                               :reason "Joining the team"
                                               :accepted-at created-at
                                               :accepted-by accepter))
                 loaded (q/get-invitation-record config bank-id "inv.store.1")
                 _ (testing "an invitation round-trips through the trio"
                     (is (= (assoc pending
                                   :reason "Joining the team"
                                   :accepted-at created-at
                                   :accepted-by accepter)
                            loaded)))
                 _ (SUT/save-invitation config pending)
                 loaded (q/get-invitation-record config bank-id "inv.store.1")
                 _ (testing
                     "an invitation without its optional fields omits them"
                     (is (= pending loaded)))
                 _ (SUT/save-invitation
                    config
                    (invitation bank-id
                                "inv.store.2" "other@example.com"
                                :invitation-status-declined "hash-2"))
                 _ (SUT/save-invitation
                    config
                    (invitation bank-id
                                "inv.store.3" "third@example.com"
                                :invitation-status-accepted "hash-3"))
                 _ (SUT/save-invitation
                    config
                    (invitation "bnk.store.elsewhere"
                                "inv.store.4" "invitee@example.com"
                                :invitation-status-pending "hash-4"))
                 _ (testing "the primary key includes the bank"
                     (is (= :invitation/not-found
                            (error/kind (q/get-invitation-record
                                         config
                                         "bnk.store.elsewhere"
                                         "inv.store.1")))))
                 by-bank (q/list-invitations-by-bank config bank-id)
                 _ (testing
                     "by bank answers every status but declined and withdrawn"
                     (is (= #{"inv.store.1" "inv.store.3"}
                            (set (map :invitation-id by-bank)))))
                 by-email (q/list-pending-invitations-by-email
                           config
                           "INVITEE@example.com"
                           {:now created-at})
                 _ (testing "by lower-cased email answers across banks"
                     (is (= #{"inv.store.1" "inv.store.4"}
                            (set (map :invitation-id by-email)))))
                 by-hash (q/get-invitation-record-for-recipient config
                                                                "inv.store.3"
                                                                {:token-hash
                                                                 "hash-3"})
                 _ (testing "by token hash answers the one invitation"
                     (is (= "inv.store.3" (:invitation-id by-hash))))
                 _ (testing "an unknown token hash is not found"
                     (is (= :invitation/not-found
                            (error/kind (q/get-invitation-record-for-recipient
                                         config
                                         "inv.store.3"
                                         {:token-hash "hash-9"})))))]))))

(deftest taken-token-hash-is-refused-test
  (with-test-system
   [sys config-file]
   (let [config (fdb-config sys)
         bank-id "bnk.store.token"
         token-hash
         "5f2b0c8e1d7a4b39a6e0c3f18d92b7e4c1a05d6f3e8b2c7a9d4f10e6b3c8a2d5"]
     (nom-test> [_ (SUT/save-invitation
                    config
                    (invitation bank-id
                                "inv.token.1" "first@example.com"
                                :invitation-status-pending token-hash))])
     (let [result (SUT/save-invitation
                   config
                   (invitation bank-id
                               "inv.token.2" "second@example.com"
                               :invitation-status-pending token-hash))]
       (testing "a second invitation under a taken token hash is refused"
         (is (SUT/uniqueness-violation? result))
         (is (not (str/includes? (pr-str result) token-hash))))
       (testing "and nothing is written"
         (is (= :invitation/not-found
                (error/kind
                 (q/get-invitation-record config bank-id "inv.token.2")))))))))

(deftest invitation-changelog-carries-created-and-resent-only-test
  (with-test-system
   [sys config-file]
   (let [config (fdb-config sys)
         bank-id "bnk.store.changelog"
         seen (atom [])
         pending (invitation bank-id
                             "inv.changelog.1" "changelog@example.com"
                             :invitation-status-pending "inv.changelog.1")]
     (nom-test> [_ (SUT/save-invitation config pending "invitation-created")
                 _ (SUT/save-invitation
                    config
                    (assoc pending :expires-at 1700700000000)
                    "invitation-resent")
                 _ (SUT/save-invitation
                    config
                    (assoc pending :status :invitation-status-accepted))
                 _ (fdb/process-changelog
                    (:record-db config)
                    "membership-changelog-read-back"
                    "invitations"
                    (fn [_ctx bytes]
                      (swap! seen conj (schema/pb->ChangelogEvent bytes)))
                    {:deduplicate? false
                     :keyspace-prefix
                     (system/instance sys [:fdb :keyspace-prefix])})
                 _
                 (testing
                   "a create and a resend each write one entry, an accept none"
                   (is (= ["invitation-created" "invitation-resent"]
                          (mapv :event-name @seen)))
                   (is (= ["inv.changelog.1:1700604800000"
                           "inv.changelog.1:1700700000000"]
                          (mapv :dedup-key @seen))))]))))

(deftest role-change-round-trips-test
  (with-test-system
   [sys config-file]
   (let [config (fdb-config sys)
         bank-id "bnk.store.history"
         change {:bank-id bank-id
                 :membership-id "mem.subject"
                 :role-change-id "rch.01j00000000000000000000001"
                 :role-before :role-viewer
                 :role-after :role-admin
                 :reason "Promoted"
                 :created-at created-at
                 :created-by owner-actor}]
     (nom-test> [_ (SUT/save-role-change config change)
                 _ (SUT/save-role-change
                    config
                    (assoc change :bank-id "bnk.store.other"))
                 found (q/list-access-events config
                                             {:bank-id bank-id
                                              :created-at 0
                                              :created-by owner-actor})
                 _ (testing "a role change reads back as its bank's history"
                     (is (= [:access-event-kind-role-changed
                             :access-event-kind-bank-created]
                            (mapv :kind found)))
                     (is (= {:kind :access-event-kind-role-changed
                             :actor owner-actor
                             :membership-id "mem.subject"
                             :role-before :role-viewer
                             :role-after :role-admin
                             :reason "Promoted"
                             :occurred-at created-at}
                            (select-keys (first found)
                                         [:kind :actor :membership-id
                                          :role-before :role-after :reason
                                          :occurred-at]))))]))))
