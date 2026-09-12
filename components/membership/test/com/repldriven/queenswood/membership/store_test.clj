(ns com.repldriven.queenswood.membership.store-test
  "The three access records against a real record store (AC-02): a
  membership written before it had a status reads back active, an
  invitation and an access event round-trip, every invitation index
  answers, a second invitation under a taken token hash is refused, and
  one bank's history pages newest first without repeats or gaps.

  The transactions live in `interface-test`; the pure rules in
  `domain-test`."
  (:require
    [com.repldriven.queenswood.fdb.interface]
    [com.repldriven.queenswood.testcontainers.interface]

    [com.repldriven.queenswood.membership.store :as SUT]

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
   :invited-by owner-actor
   :created-at 1700000000000
   :updated-at 1700000000000})

(defn- access-event
  [bank-id access-event-id]
  {:bank-id bank-id
   :access-event-id access-event-id
   :kind :access-event-kind-role-changed
   :actor owner-actor
   :subject-user-id "usr.subject"
   :membership-id "mem.subject"
   :role-before :role-viewer
   :role-after :role-admin
   :reason "Promoted"
   :occurred-at 1700000000000})

(deftest membership-without-status-reads-active-test
  (with-test-system
   [sys config-file]
   (let [config (fdb-config sys)
         bank-id "bnk.store.legacy"]
     (nom-test> [_ (SUT/save-membership config
                                        {:membership-id "mem.legacy"
                                         :user-id "usr.legacy"
                                         :bank-id bank-id
                                         :role :role-admin
                                         :created-at 1700000000000
                                         :updated-at 1700000000000})
                 loaded (SUT/get-membership config "mem.legacy")
                 _ (testing
                     "a membership saved without fields 7 to 10 reads active"
                     (is (= :membership-status-active (:status loaded)))
                     (is (= :role-admin (:role loaded)))
                     (is (not (contains? loaded :ended-at)))
                     (is (not (contains? loaded :invitation-id))))
                 active (SUT/list-active-by-bank config bank-id)
                 _ (testing "and counts as active"
                     (is (= ["mem.legacy"] (mapv :membership-id active))))
                 _ (SUT/save-membership config
                                        (assoc loaded
                                               :status :membership-status-ended
                                               :ended-at 1700000001000
                                               :ended-by owner-actor))
                 active (SUT/list-active-by-bank config bank-id)
                 listed (SUT/list-by-bank config bank-id)
                 by-user (SUT/list-active-by-user config "usr.legacy")
                 _ (testing "an ended membership is listed but not active"
                     (is (= [] active))
                     (is (= [] by-user))
                     (is (= ["mem.legacy"] (mapv :membership-id listed))))]))))

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
                                               :accepted-by-user-id
                                               "usr.accepted"))
                 loaded (SUT/find-invitation config bank-id "inv.store.1")
                 _ (testing "an invitation round-trips through the trio"
                     (is (= (assoc pending
                                   :reason "Joining the team"
                                   :accepted-by-user-id "usr.accepted")
                            loaded)))
                 _ (SUT/save-invitation config pending)
                 loaded (SUT/find-invitation config bank-id "inv.store.1")
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
                 missing (SUT/find-invitation config
                                              "bnk.store.elsewhere"
                                              "inv.store.1")
                 _ (testing "the primary key includes the bank"
                     (is (nil? missing)))
                 by-bank (SUT/list-invitations-by-bank config bank-id)
                 _ (testing
                     "by bank answers every status but declined and withdrawn"
                     (is (= #{"inv.store.1" "inv.store.3"}
                            (set (map :invitation-id by-bank)))))
                 by-email (SUT/list-invitations-by-email config
                                                         "invitee@example.com")
                 _ (testing "by lower-cased email answers across banks"
                     (is (= #{"inv.store.1" "inv.store.4"}
                            (set (map :invitation-id by-email)))))
                 by-hash (SUT/find-invitation-by-token-hash config "hash-3")
                 _ (testing "by token hash answers the one invitation"
                     (is (= "inv.store.3" (:invitation-id by-hash))))
                 unknown (SUT/find-invitation-by-token-hash config "hash-9")
                 _ (testing "an unknown token hash answers nil"
                     (is (nil? unknown)))]))))

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
       (nom-test> [kept (SUT/find-invitation config bank-id "inv.token.2")
                   _ (testing "and nothing is written" (is (nil? kept)))])))))

(deftest access-events-page-newest-first-test
  (with-test-system
   [sys config-file]
   (let [config (fdb-config sys)
         bank-id "bnk.store.history"
         ids (mapv #(format "aev.01J00000000000000000000%03d" %) (range 5))]
     (nom-test> [_ (SUT/save-access-event config
                                          (access-event bank-id (first ids)))
                 loaded (SUT/scan-access-events config bank-id {})
                 _ (testing "an access event round-trips through the trio"
                     (is (= [(access-event bank-id (first ids))]
                            (:access-events loaded))))
                 _ (doseq [id (rest ids)]
                     (SUT/save-access-event config (access-event bank-id id)))
                 _ (SUT/save-access-event config
                                          (access-event
                                           "bnk.store.other"
                                           "aev.01J00000000000000000000999"))
                 first-page (SUT/scan-access-events config bank-id {:limit 2})
                 second-page (SUT/scan-access-events
                              config
                              bank-id
                              {:limit 2 :after (:after first-page)})
                 last-page (SUT/scan-access-events
                            config
                            bank-id
                            {:limit 2 :after (:after second-page)})
                 _ (testing
                     "one bank's events scan newest first, a page at a time"
                     (is (= (take 2 (rseq ids))
                            (map :access-event-id (:access-events first-page))))
                     (is (= (take 2 (drop 2 (rseq ids)))
                            (map :access-event-id
                                 (:access-events second-page))))
                     (is (= [(first ids)]
                            (map :access-event-id (:access-events last-page))))
                     (is (nil? (:after last-page))))
                 _ (testing
                     "and the pages together are the bank's events, once each"
                     (is (= (rseq ids)
                            (mapcat #(map :access-event-id (:access-events %))
                             [first-page second-page last-page]))))]))))
