(ns com.repldriven.queenswood.membership-query.domain-test
  (:require
    [com.repldriven.queenswood.membership-query.domain :as SUT]

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
   :invited-by {:kind :actor-kind-member :principal-id "usr.owner"}
   :created-at 1
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
  (let [m {:membership-id "mem.1" :user-id "usr.1" :bank-id "bnk.1"}]
    (testing "a loaded membership is returned"
      (is (= m (SUT/ensure-found m "mem.1"))))
    (testing "no membership is not found"
      (let [result (SUT/ensure-found nil "mem.9")]
        (is (rejected? :membership/not-found result))
        (is (= "mem.9" (:membership-id (error/payload result))))))))

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
