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

(defn- role-changed
  [bank-id member-id role]
  {:bank-id bank-id
   :kind :audit-event-kind-member-role-changed
   :subject-kind :audit-subject-kind-member
   :subject-id member-id
   :request-id "01a124c5-a4c6-7578-92a9-fc765876a148"
   :details {:role-after role :nested {:depth [1 2]}}
   :created-by owner})

(deftest record-and-list-test
  (with-test-system
   [sys config-file]
   (let [config (fdb-config sys)
         bank-id "bnk.audit"
         record (fn [& events]
                  (fdb/transact
                   config
                   (fn [txn] (doseq [event events] (SUT/record txn event)))))]
     (nom-test> [_ (record (role-changed bank-id "mem.a" :role-admin)
                           (role-changed bank-id "mem.b" :role-owner))
                 _ (record (assoc (role-changed bank-id "mem.a" :role-owner)
                                  :created-by
                                  nil))
                 _ (record (role-changed bank-id "mem.a" :role-developer)
                           (role-changed "bnk.other" "mem.a" :role-owner))
                 {:keys [audit-events]}
                 (SUT/list-by-bank config bank-id {:limit 10})
                 _ (testing "newest first, the platform's act not recorded"
                     (is (= ["role-developer" "role-owner" "role-admin"]
                            (mapv (comp :role-after :details) audit-events))))
                 _ (testing "an event reads back as it was recorded"
                     (is (= {:bank-id bank-id
                             :kind :audit-event-kind-member-role-changed
                             :subject-kind :audit-subject-kind-member
                             :subject-id "mem.a"
                             :request-id "01a124c5-a4c6-7578-92a9-fc765876a148"
                             :details {:role-after "role-developer"
                                       :nested {:depth [1 2]}}
                             :created-by owner}
                            (dissoc (first audit-events)
                             :audit-event-id
                             :created-at))))
                 _ (testing "ids are aev. and sort in commit order"
                     (is (every? (fn [{:keys [audit-event-id]}]
                                   (re-matches #"aev\.[0-9a-hjkmnp-tv-z]{26}"
                                               audit-event-id))
                                 audit-events))
                     (is (= (map :audit-event-id audit-events)
                            (sort #(compare %2 %1)
                                  (map :audit-event-id audit-events)))))
                 first-page (SUT/list-by-bank config bank-id {:limit 2})
                 next-page (SUT/list-by-bank config
                                             bank-id
                                             {:limit 2
                                              :after (:after first-page)})
                 _ (testing "a page's cursor resumes after it"
                     (is (= (map :audit-event-id audit-events)
                            (map :audit-event-id
                                 (concat (:audit-events first-page)
                                         (:audit-events next-page)))))
                     (is (nil? (:after next-page))))
                 {subject-events :audit-events}
                 (SUT/list-by-subject config bank-id "mem.a" {:limit 10})
                 _ (testing "a subject's history holds its own acts in its bank"
                     (is (= ["role-developer" "role-admin"]
                            (mapv (comp :role-after :details)
                                  subject-events))))]))))

(defn- of-kind
  [bank-id kind n]
  {:bank-id bank-id
   :kind kind
   :subject-kind :audit-subject-kind-member
   :subject-id (str "mem." n)
   :details {:n (str n)}
   :created-by owner})

(deftest kinds-test
  (with-test-system
   [sys config-file]
   (let [config (fdb-config sys)
         bank-id "bnk.kinds"
         kinds [:audit-event-kind-member-removed :audit-event-kind-member-left]
         sequence [:audit-event-kind-member-removed
                   :audit-event-kind-member-role-changed
                   :audit-event-kind-member-left
                   :audit-event-kind-member-removed
                   :audit-event-kind-member-role-changed
                   :audit-event-kind-member-left]
         reasons (fn [page] (mapv (comp :n :details) (:audit-events page)))
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
                 one (list {:kinds [:audit-event-kind-member-role-changed]
                            :limit 10})
                 _ (is (= ["4" "1"] (reasons one)) "a single kind")]))))
