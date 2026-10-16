(ns com.repldriven.queenswood.calc.interest-test
  (:require
    [com.repldriven.queenswood.calc.interest :as SUT]

    [clojure.test :refer [deftest is testing]]))

(def ^:private jan-1-2024 19723)
(def ^:private jan-1-2025 20089)

(deftest year-basis-test
  (is (= 365 (SUT/year-basis :actual-365 jan-1-2024)))
  (is (= 360 (SUT/year-basis :actual-360 jan-1-2024)))
  (testing "actual/actual takes the days in the day's own year"
    (is (= 366 (SUT/year-basis :actual-actual jan-1-2024)))
    (is (= 365 (SUT/year-basis :actual-actual jan-1-2025))))
  (is (nil? (SUT/year-basis :thirty-360 jan-1-2025))))

(def ^:private relative
  {:relative {:unit :month
              :periods [{:after 0 :rates-bps [100]}
                        {:after 3 :rates-bps [200]}]}})

(def ^:private fixed
  {:fixed {:periods [{:from-on jan-1-2025 :rates-bps [100]}
                     {:from-on 20200 :rates-bps [200]}]}})

(deftest period-in-force-test
  (testing "a relative period starts its units after the account's day"
    (is (= [100] (:rates-bps (SUT/period-in-force relative jan-1-2025 20178))))
    (is (= [200] (:rates-bps (SUT/period-in-force relative jan-1-2025 20179)))
        "1 April 2025, three months after 1 January")
    (is (= [200] (:rates-bps (SUT/period-in-force relative jan-1-2025 30000)))))
  (testing "a fixed period starts on its date"
    (is (= [100] (:rates-bps (SUT/period-in-force fixed 0 20199))))
    (is (= [200] (:rates-bps (SUT/period-in-force fixed 0 20200)))))
  (testing "no period is in force before the first starts"
    (is (nil? (SUT/period-in-force relative jan-1-2025 20088)))
    (is (nil? (SUT/period-in-force fixed 0 20088)))))

(deftest band-portions-test
  (let [bands [0 1000 5000]]
    (testing "marginal puts each part of the principal in its own band"
      (is (= [1000 4000 2000] (SUT/band-portions :marginal bands 7000)))
      (is (= [1000 0 0] (SUT/band-portions :marginal bands 1000)))
      (is (= [500 0 0] (SUT/band-portions :marginal bands 500))))
    (testing "whole-balance puts the principal in the highest band it reaches"
      (is (= [0 0 7000] (SUT/band-portions :whole-balance bands 7000)))
      (is (= [0 1000 0] (SUT/band-portions :whole-balance bands 1000)))
      (is (= [999 0 0] (SUT/band-portions :whole-balance bands 999))))
    (testing "a principal at or below zero is in no band"
      (is (= [0 0 0] (SUT/band-portions :marginal bands 0)))
      (is (= [0 0 0] (SUT/band-portions :marginal bands -300)))
      (is (= [0 0 0] (SUT/band-portions :whole-balance bands 0)))
      (is (= [0 0 0] (SUT/band-portions :whole-balance bands -300))))
    (is (nil? (SUT/band-portions :tiered bands 7000)))))

(deftest annual-micro-test
  (testing "each portion at its own rate, in millionths of a minor unit"
    (is (= 150000000 (SUT/annual-micro [1000 4000 2000] [100 200 300]))))
  (is (= 0 (SUT/annual-micro [0 0] [100 200]))))

(deftest day-share-test
  ;; £10.00 (1000 minor units) at 100 bps: 1000 * 100 * 100 =
  ;; 10_000_000 millionths a year.
  (let [annual 10000000]
    (testing "a share too small to earn a unit is carried"
      (is (= {:amount 0 :carry 27397} (SUT/day-share annual 0 365))))
    (testing "the opening carry is spent, and a larger one carried"
      (is (= {:amount 0 :carry 54794} (SUT/day-share annual 27397 365))))
    (testing "a carry tips a whole unit over"
      (is (= {:amount 1 :carry 26397} (SUT/day-share annual 999000 365))))
    (testing "the carry passes whole into a 366-day year"
      ;; 10_000_000 / 366 = 27_322, plus yesterday's 27_397.
      (is (= {:amount 0 :carry 54719} (SUT/day-share annual 27397 366)))))
  (testing "a large balance crosses whole units"
    ;; £100,000 at 365 bps: 365_000_000_000 / 365.
    (is (= {:amount 1000 :carry 0} (SUT/day-share 365000000000 0 365)))))

(def ^:private accrual
  {:banding :marginal
   :day-count :actual-365
   :bands [0 100000]
   :schedule {:relative {:unit :day
                         :periods [{:after 0 :rates-bps [100 200]}
                                   {:after 10 :rates-bps [0 0]}]}}})

(deftest day-rates-test
  (testing "the period in force and the year's days"
    (is (=
         {:banding :marginal :bands [0 100000] :rates-bps [100 200] :basis 365}
         (SUT/day-rates accrual jan-1-2025 jan-1-2025)))
    (is (= [0 0]
           (:rates-bps (SUT/day-rates accrual jan-1-2025 (+ jan-1-2025 10)))))
    (is (= 360
           (:basis (SUT/day-rates (assoc accrual :day-count :actual-360)
                                  jan-1-2025
                                  jan-1-2025)))))
  (testing "none before the schedule's first period"
    (is (nil? (SUT/day-rates accrual jan-1-2025 (dec jan-1-2025)))))
  (testing "none under a day count it does not know"
    (is (nil? (SUT/day-rates (assoc accrual :day-count :thirty-360)
                             jan-1-2025
                             jan-1-2025)))))

(defn- accrue
  [accrual principal]
  (SUT/accrue (SUT/day-rates accrual jan-1-2025 jan-1-2025) principal 0))

(deftest accrue-test
  (testing "a marginal day: £1,000 at 1% and £500 at 2%"
    ;; (100_000 * 100 + 50_000 * 200) * 100 = 2_000_000_000 a year.
    (is (= {:amount 5 :carry 479452} (accrue accrual 150000))))
  (testing "a whole-balance day: all £1,500 at 2%"
    ;; 150_000 * 200 * 100 = 3_000_000_000 a year.
    (is (= {:amount 8 :carry 219178}
           (accrue (assoc accrual :banding :whole-balance) 150000))))
  (testing "actual/360 divides the year by 360"
    (is (= {:amount 5 :carry 555555}
           (accrue (assoc accrual :day-count :actual-360) 150000))))
  (testing "the opening carry is spent"
    (is (= {:amount 6 :carry 479452}
           (SUT/accrue (SUT/day-rates accrual jan-1-2025 jan-1-2025)
                       150000
                       1000000))))
  (testing "nothing accrues"
    (testing "in a period at zero"
      (is (nil? (SUT/accrue (SUT/day-rates accrual jan-1-2025 (+ jan-1-2025 10))
                            150000
                            0))))
    (testing "on an overdrawn principal" (is (nil? (accrue accrual -5000))))
    (testing "under a banding it does not know"
      (is (nil? (accrue (assoc accrual :banding :tiered) 150000))))))
