(ns com.repldriven.queenswood.cash-account-product-query.domain-test
  "Pure-function tests for `active-version` — the published-version
  resolver account-opening uses. Moved here with `active-version` when
  the product reads split into the query brick."
  (:require
    [com.repldriven.queenswood.cash-account-product-query.domain :as SUT]

    [clojure.test :refer [deftest is testing]]))

(defn- pub
  [vn from to]
  (cond-> {:version-id (str "prv." vn)
           :version-number vn
           :status :version-status-published
           :effective-from from}
          to
          (assoc :effective-to to)))

(deftest active-version-test
  (let [product {:versions [(pub 1 100 200)    ;; [100, 200)
                            (pub 2 200 nil)    ;; [200, inf)
                            (pub 3 300 nil)]}] ;; [300, inf)
    (testing "picks the window containing the day"
      (is (= 1 (:version-number (SUT/active-version product 150))))
      (is (= 2 (:version-number (SUT/active-version product 250)))
          "day 250: v1 expired, v2 active, v3 not yet"))
    (testing "overlap resolves to the greatest effective-from"
      (is (= 3 (:version-number (SUT/active-version product 350)))
          "day 350: both v2 and v3 apply; v3 has the later from"))
    (testing "no version effective on the day yields nil"
      (is (nil? (SUT/active-version {:versions [(pub 1 100 200)]} 250))))
    (testing "drafts are never active"
      (is (nil? (SUT/active-version {:versions [(assoc (pub 1 100 nil)
                                                       :status
                                                       :version-status-draft)]}
                                    250))))))
