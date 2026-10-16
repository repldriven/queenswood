(ns com.repldriven.queenswood.calc.calendar-test
  (:require
    [com.repldriven.queenswood.calc.calendar :as SUT]

    [clojure.test :refer [deftest is testing]]))

(def ^:private fri-16-oct-2026 20742)
(def ^:private sat-17-oct-2026 20743)
(def ^:private mon-19-oct-2026 20745)

(deftest business-day?-test
  (testing "no calendar counts every day"
    (is (SUT/business-day? {} sat-17-oct-2026)))
  (testing "a weekend calendar closes Saturday and Sunday"
    (is (SUT/business-day? SUT/weekends fri-16-oct-2026))
    (is (not (SUT/business-day? SUT/weekends sat-17-oct-2026)))
    (is (not (SUT/business-day? SUT/weekends (inc sat-17-oct-2026)))))
  (testing "a holiday closes its day"
    (is (not (SUT/business-day? {:weekends #{} :holidays #{fri-16-oct-2026}}
                                fri-16-oct-2026)))))

(deftest target-closing-days-test
  (testing
    "TARGET closes New Year, Good Friday, Easter Monday, 1 May and
  25 and 26 December"
    (is (= #{20454 20546 20549 20574 20812 20813}
           (SUT/target-closing-days 2026))))
  (testing "a calendar of the years asked for, closed at weekends too"
    (let [target (SUT/target [2026])]
      (is (not (SUT/business-day? target 20546)) "Good Friday")
      (is (not (SUT/business-day? target sat-17-oct-2026)))
      (is (SUT/business-day? target 20545)))))

(deftest adjust-test
  (testing "unadjusted keeps the day"
    (is (= sat-17-oct-2026
           (SUT/adjust :unadjusted SUT/weekends sat-17-oct-2026))))
  (testing "a business day stays put under every convention"
    (doseq [convention [:following :preceding :modified-following
                        :modified-preceding]]
      (is (= fri-16-oct-2026
             (SUT/adjust convention SUT/weekends fri-16-oct-2026)))))
  (testing "following and preceding move to the nearest business day"
    (is (= mon-19-oct-2026
           (SUT/adjust :following SUT/weekends sat-17-oct-2026)))
    (is (= fri-16-oct-2026
           (SUT/adjust :preceding SUT/weekends sat-17-oct-2026))))
  (testing "modified following moves back rather than into the next month"
    ;; Saturday 31 January 2026: following is Monday 2 February.
    (is (= 20486 (SUT/adjust :following SUT/weekends 20484)))
    (is (= 20483 (SUT/adjust :modified-following SUT/weekends 20484))))
  (testing "modified preceding moves on rather than into the last month"
    ;; Saturday 1 August 2026: preceding is Friday 31 July.
    (is (= 20665 (SUT/adjust :preceding SUT/weekends 20666)))
    (is (= 20668 (SUT/adjust :modified-preceding SUT/weekends 20666))))
  (testing "holidays and weekends together"
    ;; Good Friday 3 April 2026 to Easter Monday 6 April: Tuesday 7 April.
    (is (= 20550 (SUT/adjust :following (SUT/target [2026]) 20546))))
  (testing "no business day within a month is nil"
    (is (nil? (SUT/adjust :following
                          {:weekends #{1 2 3 4 5 6 7} :holidays #{}}
                          fri-16-oct-2026))))
  (is (nil? (SUT/adjust :end-of-month SUT/weekends fri-16-oct-2026))))
