(ns com.repldriven.queenswood.calc.payment-test
  (:require
    [com.repldriven.queenswood.calc.calendar :as calendar]
    [com.repldriven.queenswood.calc.payment :as SUT]

    [clojure.test :refer [deftest is testing]]))

(def ^:private sat-31-jan-2026 20484)

(def ^:private daily {:account-day {:unit :day :every 1}})

(def ^:private monthly {:account-day {:unit :month :every 1}})

(def ^:private quarterly-on-the-31st
  {:calendar-day {:every 3 :day-of-month 31 :in-month 3}})

(deftest scheduled-days-test
  (testing "an account-day payment counts from the account's day"
    (is (= [20484 20485 20486 20487]
           (SUT/scheduled-days daily sat-31-jan-2026 20480 20487)))
    (is (= [20498 20512]
           (SUT/scheduled-days {:account-day {:unit :week :every 2}}
                               sat-31-jan-2026
                               sat-31-jan-2026
                               20520))))
  (testing "a month lands on the month's last day where it has fewer"
    ;; 28 February, 31 March, 30 April, 31 May.
    (is (= [20512 20543 20573 20604]
           (SUT/scheduled-days monthly sat-31-jan-2026 20480 20604))))
  (testing "a calendar-day payment falls in the months it names"
    ;; The last days of March, June, September and December 2026.
    (is (= [20543 20634 20726 20818]
           (SUT/scheduled-days quarterly-on-the-31st nil 20454 20818))))
  (testing "never before the day the account came onto its version"
    (is (= [20634 20726 20818]
           (SUT/scheduled-days quarterly-on-the-31st 20600 20454 20818)))
    (is (= [20487] (SUT/scheduled-days daily 20487 20480 20487))
        "a daily payment pays the first day too")
    (is (= [] (SUT/scheduled-days monthly 20487 20480 20487))
        "a longer cadence first pays one period after"))
  (testing "a payment at close schedules none"
    (is (= [] (SUT/scheduled-days {:closure-day {}} 20000 20480 20487)))))

(deftest due?-test
  (testing "a daily payment on no calendar is due every day"
    (is (SUT/due? daily sat-31-jan-2026 {} :unadjusted 20485 20486))
    (is (SUT/due? daily 20486 {} :unadjusted 20485 20486)
        "the day the account came onto its version among them")
    (is (not (SUT/due? daily 20487 {} :unadjusted 20485 20486))
        "but none before it"))
  (testing "a daily payment on weekdays pays the weekend on Monday"
    (is (not (SUT/due? daily
                       20000
                       calendar/weekends
                       :following
                       20483
                       sat-31-jan-2026)))
    (is (SUT/due? daily 20000 calendar/weekends :following 20485 20486)))
  (testing "a convention moves a payment day off a closed one"
    ;; Monthly from 31 January: Saturday 28 February moves back to Friday
    ;; 27 February, since following would cross into March.
    (is (SUT/due? monthly
                  sat-31-jan-2026
                  calendar/weekends
                  :modified-following
                  20510
                  20511))
    (is (not (SUT/due? monthly
                       sat-31-jan-2026
                       calendar/weekends
                       :modified-following
                       20511
                       20512))))
  (testing "a calendar-day payment moves too"
    ;; The 31st of each month: Saturday 31 October moves to Friday 30th.
    (is (SUT/due? {:calendar-day {:every 1 :day-of-month 31 :in-month 1}}
                  20000
                  calendar/weekends
                  :modified-following
                  20755
                  20756)))
  (testing "a window reaching back over missed days pays them"
    (is (SUT/due? monthly sat-31-jan-2026 {} :unadjusted 20480 20520))
    (is (not (SUT/due? monthly sat-31-jan-2026 {} :unadjusted 20513 20520))))
  (testing "with no run before, the window is the run's own day"
    (is (SUT/due? monthly sat-31-jan-2026 {} :unadjusted nil 20512))
    (is (not (SUT/due? monthly sat-31-jan-2026 {} :unadjusted nil 20520)))))
