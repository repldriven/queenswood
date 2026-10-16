(ns com.repldriven.queenswood.interest.domain.payment-test
  "Whether an account is due a payment under its version's payment terms,
  and what that depends on."
  (:require
    [com.repldriven.queenswood.interest.domain.payment :as SUT]

    [com.repldriven.queenswood.calc.interface :as calc]

    [clojure.test :refer [deftest is testing]]))

(def ^:private sat-31-jan-2026 20484)

(defn- monthly
  [calendar]
  {:business-day-convention :business-day-convention-modified-following
   :kind {:account-day {:unit :interest-period-unit-month :every 1}}
   :calendar calendar})

(deftest due?-test
  (testing "a version's terms are read in calc's vocabulary"
    ;; Monthly from 31 January: 28 February, a Saturday, moves back to
    ;; Friday 27 February on weekdays.
    (is (SUT/due? (monthly :business-day-calendar-weekends)
                  sat-31-jan-2026
                  calc/weekends
                  20510
                  20511))
    (is (SUT/due? (monthly :business-day-calendar-none)
                  sat-31-jan-2026
                  {}
                  20511
                  20512)))
  (testing "a calendar-day payment"
    (is (SUT/due? {:business-day-convention :business-day-convention-unadjusted
                   :kind {:calendar-day {:every 3 :day-of-month 31 :in-month 3}}
                   :calendar :business-day-calendar-none}
                  20000 {}
                  20542 20543)))
  (testing "a payment at close is never due on a schedule"
    (is (not (SUT/due? {:business-day-convention
                        :business-day-convention-unadjusted
                        :kind {:closure-day {}}
                        :calendar :business-day-calendar-none}
                       20000 {}
                       20000 30000)))))

(def ^:private account
  {:product-id "prd.1" :version-id "ver.1" :version-from-on 20000})

(def ^:private quarterly
  {:kind {:calendar-day {:every 3 :day-of-month 31 :in-month 3}}})

(deftest due-key-test
  (testing "an account-day payment depends on the account's day"
    (is
     (=
      ["prd.1" "ver.1" 20000]
      (SUT/due-key account (monthly :business-day-calendar-none) 20542 20543))))
  (testing "a calendar-day payment on the version alone"
    (is (= ["prd.1" "ver.1" nil] (SUT/due-key account quarterly 20542 20543))))
  (testing "unless the account came onto it within the window's reach"
    (is (= ["prd.1" "ver.1" 20530]
           (SUT/due-key (assoc account :version-from-on 20530)
                        quarterly
                        20542
                        20543)))))
