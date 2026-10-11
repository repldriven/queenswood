(ns com.repldriven.queenswood.access-log.interface-test
  (:require
    [com.repldriven.queenswood.fdb.interface]
    [com.repldriven.queenswood.testcontainers.interface]

    [com.repldriven.queenswood.access-log.interface :as SUT]

    [com.repldriven.mono.system.interface :as system]
    [com.repldriven.mono.test-system.interface :refer
     [with-test-system nom-test>]]

    [clojure.test :refer [deftest is testing]]))

(def ^:private config-file "classpath:access-log/application-test.yml")

(defn- fdb-config
  [sys]
  {:record-db (system/instance sys [:fdb :record-db])
   :record-store (system/instance sys [:fdb :meta-store])})

(def ^:private member {:kind :actor-kind-member :principal-id "usr.member"})

(defn- call
  [bank-id n requested-at]
  {:bank-id bank-id
   :request-id (str "req-" n)
   :idempotency-key (str "key-" n)
   :correlation-id "checkout-1"
   :requested-at requested-at
   :requested-by member
   :method "POST"
   :route "/v1/cash-accounts/{account-id}/suspend"
   :operation-id "SuspendCashAccount"
   :tag "Cash Accounts"
   :path-ids {:account-id (str "acc." n)}
   :http-status 409
   :problem-type ":cash-account/invalid-status"
   :replayed false
   :client-address "203.0.113.7"
   :duration 41250})

(defn- request-ids
  [page]
  (mapv :request-id (:access-entries page)))

(defn- written
  "The bank's log once it holds `n` entries, polled while the writer
  drains."
  [config bank-id n]
  (loop [tries 100]
    (let [page (SUT/list-by-bank config bank-id {:limit 50})]
      (if (or (= n (count (:access-entries page))) (zero? tries))
        page
        (do (Thread/sleep 50) (recur (dec tries)))))))

(deftest record-and-list-test
  (with-test-system
   [sys config-file]
   (let [config (fdb-config sys)
         writer (system/instance sys [:access-log :writer])
         bank-id "bnk.access"]
     (doseq [n (range 3)] (SUT/record writer (call bank-id n (+ 1000 n))))
     (SUT/record writer
                 (assoc (call "bnk.other" 9 1000)
                        :idempotency-key
                        (apply str (repeat 300 "k"))))
     (nom-test> [log (written config bank-id 3)
                 _ (testing "a bank's log holds its own calls, newest first"
                     (is (= ["req-2" "req-1" "req-0"] (request-ids log)))
                     (is (= {:http-status 409
                             :path-ids {:account-id "acc.2"}
                             :requested-by member
                             :replayed false}
                            (select-keys (first (:access-entries log))
                                         [:http-status :path-ids
                                          :requested-by :replayed])))
                     (is (re-matches #"axs\.[0-9a-z]{26}"
                                     (:access-entry-id (first (:access-entries
                                                               log))))))
                 first-page (SUT/list-by-bank config bank-id {:limit 2})
                 next-page (SUT/list-by-bank config
                                             bank-id
                                             {:limit 2
                                              :after (:after first-page)})
                 _ (testing "a page's cursor resumes after it"
                     (is (= ["req-2" "req-1"] (request-ids first-page)))
                     (is (= ["req-0"] (request-ids next-page)))
                     (is (nil? (:after next-page))))
                 by-request
                 (SUT/list-by-bank config bank-id {:limit 10 :ref "req-1"})
                 by-key
                 (SUT/list-by-bank config bank-id {:limit 10 :ref "key-0"})
                 by-correlation
                 (SUT/list-by-bank config bank-id {:limit 10 :ref "checkout-1"})
                 _ (testing "a call is found by each id it carried"
                     (is (= ["req-1"] (request-ids by-request)))
                     (is (= ["req-0"] (request-ids by-key)))
                     (is (= ["req-2" "req-1" "req-0"]
                            (request-ids by-correlation))))
                 other (written config "bnk.other" 1)
                 _ (testing "a client's id is cut to 255 characters"
                     (is (= 255
                            (count (:idempotency-key (first (:access-entries
                                                             other)))))))
                 elsewhere
                 (SUT/list-by-bank config "bnk.other" {:limit 10 :ref "req-1"})
                 _ (is (empty? (:access-entries elsewhere))
                       "a reference finds only its own bank's calls")]))))

(deftest tags-test
  (with-test-system
   [sys config-file]
   (let [config (fdb-config sys)
         writer (system/instance sys [:access-log :writer])
         bank-id "bnk.tags"
         tagged (fn [n tag] (assoc (call bank-id n (+ 1000 n)) :tag tag))]
     (doseq [[n tag] (map-indexed vector
                                  ["Payments" "Cash Accounts" "Members"
                                   "Payments" "Cash Accounts" "Members"])]
       (SUT/record writer (tagged n tag)))
     (nom-test> [_ (written config bank-id 6)
                 first-page (SUT/list-by-bank config
                                              bank-id
                                              {:limit 3
                                               :tags ["Payments"
                                                      "Cash Accounts"]})
                 next-page (SUT/list-by-bank config
                                             bank-id
                                             {:limit 3
                                              :tags ["Payments" "Cash Accounts"]
                                              :after (:after first-page)})
                 _ (testing "a set of tags pages newest first across them"
                     (is (= ["req-4" "req-3" "req-1"] (request-ids first-page)))
                     (is (= ["req-0"] (request-ids next-page)))
                     (is (nil? (:after next-page))))
                 by-ref (SUT/list-by-bank
                         config
                         bank-id
                         {:limit 10 :ref "checkout-1" :tags ["Members"]})
                 _ (is (= ["req-5" "req-2"] (request-ids by-ref))
                       "tags narrow a reference's calls")
                 _ (SUT/delete-before config bank-id 1002 10)
                 after-delete (SUT/list-by-bank config
                                                bank-id
                                                {:limit 10 :tags ["Payments"]})
                 _ (is (= ["req-3"] (request-ids after-delete))
                       "a deleted call leaves its tag")]))))

(deftest delete-before-test
  (with-test-system
   [sys config-file]
   (let [config (fdb-config sys)
         writer (system/instance sys [:access-log :writer])
         bank-id "bnk.expiring"]
     (doseq [n (range 4)] (SUT/record writer (call bank-id n (+ 1000 n))))
     (nom-test> [_ (written config bank-id 4)
                 first-pass (SUT/delete-before config bank-id 1003 2)
                 second-pass (SUT/delete-before config bank-id 1003 2)
                 _ (testing "deletes the oldest, a limit at a time"
                     (is (= 2 first-pass))
                     (is (= 1 second-pass)))
                 log (SUT/list-by-bank config bank-id {:limit 10})
                 _ (is (= ["req-3"] (request-ids log)) "newer calls stay")
                 by-correlation
                 (SUT/list-by-bank config bank-id {:limit 10 :ref "checkout-1"})
                 _ (is (= ["req-3"] (request-ids by-correlation))
                       "a deleted call's references go with it")]))))

(deftest full-queue-test
  (with-test-system
   [sys config-file]
   (let [writer (system/instance sys [:access-log :held-writer])]
     (doseq [n (range 3)] (SUT/record writer (call "bnk.full" n 1000)))
     (is (= 1 (SUT/dropped writer))
         "an entry the full queue refuses is dropped and counted"))))
