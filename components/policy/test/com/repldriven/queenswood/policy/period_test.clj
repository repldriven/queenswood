(ns com.repldriven.queenswood.policy.period-test
  (:require
    [com.repldriven.queenswood.policy.period :as SUT]

    [clojure.test :refer [deftest is testing]])
  (:import
    (java.time LocalDate)))

(defn- day [iso] (.toEpochDay (LocalDate/parse iso)))

(defn- start
  [period on opened]
  (str (LocalDate/ofEpochDay (SUT/start period
                                        (day on)
                                        (some-> opened
                                                day)))))

(deftest account-anchored-periods-test
  (testing "a month runs from the day of the month the account opened on"
    (is (= "2026-10-17"
           (start {:window :time-window-monthly} "2026-11-16" "2026-03-17")))
    (is (= "2026-11-17"
           (start {:window :time-window-monthly} "2026-11-17" "2026-03-17"))))
  (testing "an account opened on the 31st starts a shorter month on its last"
    (is (= "2026-02-28"
           (start {:window :time-window-monthly} "2026-03-05" "2026-01-31"))))
  (testing "a quarter and a year step from the opening day"
    (is (= "2026-09-17"
           (start {:window :time-window-quarterly} "2026-12-01" "2026-03-17")))
    (is (= "2026-03-17"
           (start {:window :time-window-annually} "2027-03-16" "2026-03-17")))
    (is (= "2027-03-17"
           (start {:window :time-window-annually} "2027-03-17" "2026-03-17"))))
  (testing
    "a business day before the account opened falls in the period
            that ends on its opening day"
    (is (= "2026-02-17"
           (start {:window :time-window-monthly} "2026-03-16" "2026-03-17")))
    (is (= "2025-03-17"
           (start {:window :time-window-annually} "2026-03-16" "2026-03-17"))))
  (testing "a week runs from the weekday the account opened on"
    (is (= "2026-03-24"
           (start {:window :time-window-weekly} "2026-03-30" "2026-03-17")))))

(deftest calendar-anchored-periods-test
  (let [calendar {:anchor :window-anchor-calendar}]
    (testing "a month, a quarter and a year start on the first"
      (is (= "2026-11-01"
             (start (assoc calendar :window :time-window-monthly)
                    "2026-11-16"
                    "2026-03-17")))
      (is (= "2026-10-01"
             (start (assoc calendar :window :time-window-quarterly)
                    "2026-11-16"
                    "2026-03-17")))
      (is (= "2026-01-01"
             (start (assoc calendar :window :time-window-annually)
                    "2026-11-16"
                    "2026-03-17"))))
    (testing "a year that starts on a named day, as a tax year does"
      (let [tax-year (assoc calendar
                            :window :time-window-annually
                            :starts-month 4
                            :starts-day-of-month 6)]
        (is (= "2026-04-06" (start tax-year "2027-04-05" nil)))
        (is (= "2027-04-06" (start tax-year "2027-04-06" nil)))))
    (testing "a week starts on a Monday"
      (is (= "2026-11-16"
             (start (assoc calendar :window :time-window-weekly)
                    "2026-11-18"
                    nil))))))

(deftest unanchored-and-short-windows-test
  (testing "with no opening day a period is counted by the calendar"
    (is (= "2026-11-01"
           (start {:window :time-window-monthly} "2026-11-16" nil))))
  (testing "an instant and a day are the day itself"
    (is (= "2026-11-16"
           (start {:window :time-window-instant} "2026-11-16" "2026-03-17")))
    (is (= "2026-11-16"
           (start {:window :time-window-daily} "2026-11-16" "2026-03-17"))))
  (testing "a rolling window reaches back its whole days"
    (is (= "2026-11-09"
           (start {:window :time-window-rolling
                   :rolling-window-ms (* 7 24 60 60 1000)}
                  "2026-11-16"
                  nil)))))
