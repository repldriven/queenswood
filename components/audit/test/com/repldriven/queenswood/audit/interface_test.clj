(ns com.repldriven.queenswood.audit.interface-test
  (:require
    [com.repldriven.queenswood.fdb.interface :as fdb]
    [com.repldriven.queenswood.testcontainers.interface]

    [com.repldriven.queenswood.audit.interface :as SUT]

    [com.repldriven.mono.system.interface :as system]
    [com.repldriven.mono.test-system.interface :refer
     [with-test-system nom-test>]]

    [clojure.test :refer [deftest is testing]]))

(def ^:private config-file "classpath:audit/application-test.yml")

(defn- fdb-config
  [sys]
  {:record-db (system/instance sys [:fdb :record-db])
   :record-store (system/instance sys [:fdb :store])})

(def ^:private owner {:kind :actor-kind-member :principal-id "usr.owner"})

(defn- member
  [bank-id member-id role]
  {:bank-id bank-id
   :member-id member-id
   :status :member-status-active
   :role role
   :user-id "usr.member"
   :created-at 1
   :created-by owner})

(defn- role-changed
  [bank-id member-id role]
  (let [before (member bank-id member-id :role-viewer)]
    {:bank-id bank-id
     :kind :audit-kind-member-role-changed
     :subject-kind :audit-subject-kind-member
     :subject-id member-id
     :request-id "01a124c5-a4c6-7578-92a9-fc765876a148"
     :before before
     :after (assoc before
                   :role role
                   :role-reason "promoted"
                   :updated-at 2
                   :updated-by owner)
     :performed-by owner}))

(deftest record-and-list-test
  (with-test-system
   [sys config-file]
   (let [config (fdb-config sys)
         bank-id "bnk.audit"
         record (fn [& entries]
                  (fdb/transact
                   config
                   (fn [txn] (doseq [entry entries] (SUT/record txn entry)))))]
     (nom-test> [_ (record (role-changed bank-id "mem.a" :role-admin)
                           (role-changed bank-id "mem.b" :role-owner))
                 _ (record (assoc (role-changed bank-id "mem.a" :role-owner)
                                  :performed-by
                                  nil))
                 _ (record (role-changed bank-id "mem.a" :role-developer)
                           (role-changed "bnk.other" "mem.a" :role-owner))
                 {:keys [audit-entries]}
                 (SUT/list-by-bank config bank-id {:limit 10})
                 _ (testing "newest first, the platform's act not recorded"
                     (is (= ["role-developer" "role-owner" "role-admin"]
                            (mapv (comp :role :after :changeset)
                                  audit-entries))))
                 _ (testing "an entry reads back as it was recorded"
                     (is (= {:bank-id bank-id
                             :kind :audit-kind-member-role-changed
                             :subject-kind :audit-subject-kind-member
                             :subject-id "mem.a"
                             :request-id "01a124c5-a4c6-7578-92a9-fc765876a148"
                             :changeset {:before {:role "role-viewer"}
                                         :after {:role "role-developer"
                                                 :role-reason "promoted"}}
                             :performed-by owner}
                            (dissoc (first audit-entries)
                             :audit-entry-id
                             :performed-at))))
                 _ (testing "ids are aud. and sort in commit order"
                     (is (every? (fn [{:keys [audit-entry-id]}]
                                   (re-matches #"aud\.[0-9a-hjkmnp-tv-z]{26}"
                                               audit-entry-id))
                                 audit-entries))
                     (is (= (map :audit-entry-id audit-entries)
                            (sort #(compare %2 %1)
                                  (map :audit-entry-id audit-entries)))))
                 first-page (SUT/list-by-bank config bank-id {:limit 2})
                 next-page (SUT/list-by-bank config
                                             bank-id
                                             {:limit 2
                                              :after (:after first-page)})
                 _ (testing "a page's cursor resumes after it"
                     (is (= (map :audit-entry-id audit-entries)
                            (map :audit-entry-id
                                 (concat (:audit-entries first-page)
                                         (:audit-entries next-page)))))
                     (is (nil? (:after next-page))))
                 {subject-entries :audit-entries}
                 (SUT/list-by-subject config bank-id "mem.a" {:limit 10})
                 _ (testing "a subject's history holds its own acts in its bank"
                     (is (= ["role-developer" "role-admin"]
                            (mapv (comp :role :after :changeset)
                                  subject-entries))))]))))

(defn- of-kind
  [bank-id kind n]
  {:bank-id bank-id
   :kind kind
   :subject-kind :audit-subject-kind-member
   :subject-id (str "mem." n)
   :after {:member-id (str n)}
   :performed-by owner})

(deftest kinds-test
  (with-test-system
   [sys config-file]
   (let [config (fdb-config sys)
         bank-id "bnk.kinds"
         kinds [:audit-kind-member-removed :audit-kind-member-left]
         sequence [:audit-kind-member-removed
                   :audit-kind-member-role-changed
                   :audit-kind-member-left
                   :audit-kind-member-removed
                   :audit-kind-member-role-changed
                   :audit-kind-member-left]
         reasons (fn [page]
                   (mapv (comp :member-id :after :changeset)
                         (:audit-entries page)))
         list (fn [opts]
                (SUT/list-by-bank config
                                  bank-id
                                  (merge {:limit 2 :kinds kinds} opts)))]
     (nom-test> [_ (fdb/transact
                    config
                    (fn [txn]
                      (doseq [[n kind] (map-indexed vector sequence)]
                        (SUT/record txn (of-kind bank-id kind n)))))
                 first-page (list {})
                 _ (testing "a set of kinds pages newest first across the kinds"
                     (is (= ["5" "3"] (reasons first-page)))
                     (is (nil? (:before first-page))))
                 second-page (list {:after (:after first-page)})
                 _ (is (= ["2" "0"] (reasons second-page)))
                 _ (is (nil? (:after second-page))
                       "nothing of those kinds is older")
                 back (list {:before (:before second-page)})
                 _ (testing "paging back returns the page before"
                     (is (= ["5" "3"] (reasons back)))
                     (is (nil? (:before back))))
                 one (list {:kinds [:audit-kind-member-role-changed] :limit 10})
                 _ (is (= ["4" "1"] (reasons one)) "a single kind")]))))

(def ^:private operator
  {:kind :actor-kind-operator :principal-id "queenswood-admin"})

(def ^:private credential {:kind :actor-kind-bank :principal-id "cli.bank"})

(deftest performer-kinds-test
  (with-test-system
   [sys config-file]
   (let [config (fdb-config sys)
         bank-id "bnk.performers"
         sequence [[:audit-kind-member-removed owner]
                   [:audit-kind-member-removed operator]
                   [:audit-kind-member-left credential]
                   [:audit-kind-member-removed credential]
                   [:audit-kind-member-left operator]
                   [:audit-kind-member-removed owner]]
         ids (fn [page]
               (mapv (comp :member-id :after :changeset) (:audit-entries page)))
         list (fn [opts] (SUT/list-by-bank config bank-id opts))]
     (nom-test> [_ (fdb/transact config
                                 (fn [txn]
                                   (doseq [[n [kind performer]]
                                           (map-indexed vector sequence)]
                                     (SUT/record txn
                                                 (assoc (of-kind bank-id kind n)
                                                        :performed-by
                                                        performer)))))
                 by-performers (list {:performer-kinds [:actor-kind-operator
                                                        :actor-kind-bank]
                                      :limit 3})
                 _ (testing "a set of performer kinds pages newest first"
                     (is (= ["4" "3" "2"] (ids by-performers))))
                 older (list {:performer-kinds [:actor-kind-operator
                                                :actor-kind-bank]
                              :limit 3
                              :after (:after by-performers)})
                 _ (is (= ["1"] (ids older)))
                 _ (is (nil? (:after older)))
                 both (list {:performer-kinds [:actor-kind-operator
                                               :actor-kind-bank]
                             :kinds [:audit-kind-member-removed]
                             :limit 10})
                 _ (testing "kinds and performer kinds narrow together"
                     (is (= ["3" "1"] (ids both))))]))))

(deftest date-range-test
  (with-test-system
   [sys config-file]
   (let [config (fdb-config sys)
         bank-id "bnk.dates"
         ids (fn [page]
               (mapv (comp :member-id :after :changeset) (:audit-entries page)))
         list (fn [opts]
                (SUT/list-by-bank config bank-id (merge {:limit 10} opts)))]
     (nom-test> [_ (fdb/transact
                    config
                    (fn [txn]
                      (doseq [n (range 6)]
                        (SUT/record txn
                                    (assoc (of-kind bank-id
                                                    (if (even? n)
                                                      :audit-kind-member-removed
                                                      :audit-kind-member-left)
                                                    n)
                                           :performed-at
                                           (+ 1000 (* 10 n)))))))
                 bounded (list {:from 1010 :to 1040})
                 _ (is (= ["3" "2" "1"] (ids bounded))
                       "from is inclusive and to exclusive")
                 since (list {:from 1035})
                 _ (is (= ["5" "4"] (ids since)) "from alone is since")
                 until (list {:to 1020})
                 _ (is (= ["1" "0"] (ids until)) "to alone is until")
                 kinds (list {:from 1010
                              :to 1050
                              :kinds [:audit-kind-member-removed]})
                 _ (is (= ["4" "2"] (ids kinds)) "kinds apply inside the range")
                 first-page (list {:from 1000 :to 1050 :limit 2})
                 next-page
                 (list
                  {:from 1000 :to 1050 :limit 2 :after (:after first-page)})
                 _ (testing "pages stay inside the range"
                     (is (= ["4" "3"] (ids first-page)))
                     (is (= ["2" "1"] (ids next-page))))
                 later (list {:from 2000})
                 _ (is (empty? (:audit-entries later))
                       "nothing performed after the newest")]))))

(deftest changeset-leaves-out-test
  (with-test-system
   [sys config-file]
   (let [config (fdb-config sys)
         bank-id "bnk.left-out"]
     (nom-test>
       [_ (fdb/transact config
                        (fn [txn]
                          (SUT/record txn
                                      {:bank-id bank-id
                                       :kind :audit-kind-invitation-created
                                       :subject-kind
                                       :audit-subject-kind-invitation
                                       :subject-id "inv.a"
                                       :after {:bank-id bank-id
                                               :invitation-id "inv.a"
                                               :email "Ada@example.com"
                                               :email-lower "ada@example.com"
                                               :token-hash "ab12"
                                               :role :role-admin
                                               :idempotency-key "k"
                                               :created-at 1
                                               :created-by owner}
                                       :performed-by owner})))
        {:keys [audit-entries]}
        (SUT/list-by-subject config bank-id "inv.a" {:limit 1})
        _
        (testing
          "a create has only after, without the audit
                            block or a user or system field"
          (is (= {:after
                  {:bank-id bank-id :invitation-id "inv.a" :role "role-admin"}}
                 (:changeset (first audit-entries)))))]))))
