(ns com.repldriven.queenswood.calc.date-test
  (:require
    [com.repldriven.queenswood.calc.date :as SUT]

    [clojure.test :refer [deftest is testing]]))

(def ^:private jan-1-2024 19723)
(def ^:private jan-1-2025 20089)
(def ^:private jan-1-2100 47482)
(def ^:private jan-1-2000 10957)

(deftest days-in-year-test
  (testing "a leap year has 366 days"
    (is (= 366 (SUT/days-in-year jan-1-2024)))
    (is (= 366 (SUT/days-in-year jan-1-2000))))
  (testing "any other year has 365"
    (is (= 365 (SUT/days-in-year jan-1-2025)))
    (testing "a century not divisible by 400 is not a leap year"
      (is (= 365 (SUT/days-in-year jan-1-2100))))))

(def ^:private jan-31-2025 20119)

(deftest plus-units-test
  (testing "days and weeks count days"
    (is (= 20122 (SUT/plus-units jan-31-2025 :day 3)))
    (is (= 20126 (SUT/plus-units jan-31-2025 :week 1))))
  (testing "a month lands on the same day of the month"
    (is (= 20178 (SUT/plus-units 20088 :month 3))))
  (testing "or on the month's last day where it has fewer"
    (is (= 20147 (SUT/plus-units jan-31-2025 :month 1)))
    (is (= 19782 (SUT/plus-units 19753 :month 1)) "29 February in a leap year"))
  (testing "nought units is the day itself"
    (is (= jan-31-2025 (SUT/plus-units jan-31-2025 :month 0))))
  (testing "an unknown unit is nil"
    (is (nil? (SUT/plus-units jan-31-2025 :fortnight 1)))))

(deftest day-of-week-test
  (is (= 5 (SUT/day-of-week 20742)) "16 October 2026 is a Friday")
  (is (= 6 (SUT/day-of-week 20743)))
  (is (= 1 (SUT/day-of-week 20745))))

(deftest epoch-day-test
  (is (= jan-1-2025 (SUT/epoch-day 2025 1 1)))
  (is (= 1 (SUT/month-of jan-1-2025))))

(deftest easter-sunday-test
  (testing "Easter falls on its Gregorian date"
    (is (= 11070 (SUT/easter-sunday 2000)) "23 April")
    (is (= 18007 (SUT/easter-sunday 2019)) "21 April")
    (is (= 19813 (SUT/easter-sunday 2024)) "31 March")
    (is (= 20198 (SUT/easter-sunday 2025)) "20 April")
    (is (= 20548 (SUT/easter-sunday 2026)) "5 April")))
