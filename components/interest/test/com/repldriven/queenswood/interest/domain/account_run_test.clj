(ns com.repldriven.queenswood.interest.domain.account-run-test
  "The per-account row a pass leaves behind: which one it records on,
  and what done and failed put on it."
  (:require
    [com.repldriven.queenswood.interest.domain.account-run :as SUT]

    [clojure.test :refer [deftest is testing]]))

(def ^:private account
  {:account-id "acc.1"
   :currency "GBP"
   :product-type :product-type-sub-ledger-current})

(defn- fresh-run
  []
  (SUT/new "org.1" 20260501 :interest-run-kind-accrue account nil))

(deftest new-test
  (testing "no run yet means a fresh pending one off the account"
    (let [row (fresh-run)]
      (is (not (SUT/done? row)))
      (is (= "acc.1" (:account-id row)))
      (is (not-any? (partial contains? row) [:currency :product-type]))
      (is (number? (:created-at row)))))
  (testing "a run an earlier attempt left behind is kept, not replaced"
    ;; A re-run has to record its outcome on the existing run — a fresh
    ;; one would drop :created-at and the earlier state.
    (let [existing (SUT/failed (fresh-run) :interest/boom)]
      (is (= existing
             (SUT/new "org.1"
                      20260501
                      :interest-run-kind-accrue
                      account
                      existing))))))

(deftest outcome-test
  (testing "only done counts as done, and failed keeps a reason"
    (let [done (SUT/done (fresh-run) {:amount 7})
          failed (SUT/failed (fresh-run) :interest/boom)]
      (is (SUT/done? done))
      (is (= :interest-account-run-status-done (:status done)))
      (is (not (SUT/done? failed)))
      (is (= :interest-account-run-status-failed (:status failed)))
      (is (= ":interest/boom" (:failure-reason failed)))))
  (testing "a done run carries what was earned and what it came from"
    (let [done (SUT/done (fresh-run)
                         {:amount 7 :principal 100000 :opening-carry 12345})]
      (is (= 7 (:amount done)))
      (is (= 100000 (:principal done)))
      (is (= 12345 (:opening-carry done)))))
  (testing "an account with nothing to do is still done, and records nothing"
    ;; Both passes hand back nil when there was nothing to accrue or
    ;; sweep, and the run must take that without inventing zeroes.
    (let [done (SUT/done (fresh-run) nil)]
      (is (SUT/done? done))
      (is (not (contains? done :amount)))
      (is (not (contains? done :principal)))
      (is (not (contains? done :opening-carry)))))
  (testing "absent inputs stay absent rather than being recorded as nil"
    (let [done (SUT/done (fresh-run) {:amount 0})]
      (is (= 0 (:amount done)))
      (is (not (contains? done :principal)))
      (is (not (contains? done :opening-carry)))))
  (testing "an outcome's other keys do not leak onto the run"
    ;; An accrual also carries the balance to advance and the closing
    ;; carry; a sweep carries its transaction. None belong on the run.
    (let [done (SUT/done (fresh-run)
                         {:amount 7
                          :balance {:credit 40}
                          :closing-carry 68492
                          :transaction {:idempotency-key "k"}})]
      (is (not (contains? done :balance)))
      (is (not (contains? done :closing-carry)))
      (is (not (contains? done :transaction))))))
