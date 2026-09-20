(ns com.repldriven.queenswood.scheduler.domain-test
  "Pure-function tests for the scheduler domain: per-task periodicity
  constraints, the job-allowed intersection, periodicity→cron, and the
  expected-end estimate. No FDB, no scheduler."
  (:require
    [com.repldriven.queenswood.scheduler.domain :as SUT]

    [com.repldriven.mono.error.interface :as error]

    [clojure.test :refer [deftest is testing]]))

(deftest job-allowed-periods-test
  (testing "accrue is daily-only"
    (is (= #{:scheduler-periodicity-daily}
           (SUT/job-allowed-periods [:scheduler-task-kind-accrue]))))
  (testing "capitalize allows daily/monthly/yearly, and never hourly"
    (is (= SUT/daily-or-longer
           (SUT/job-allowed-periods [:scheduler-task-kind-capitalize])))
    (is (not (contains? (SUT/job-allowed-periods
                         [:scheduler-task-kind-capitalize])
                        :scheduler-periodicity-hourly))))
  (testing "hourly is a periodicity, so a task may allow it"
    (is (contains? SUT/all-periods :scheduler-periodicity-hourly)))
  (testing "a sequence is the intersection — accrue narrows the job to daily"
    (is (= #{:scheduler-periodicity-daily}
           (SUT/job-allowed-periods [:scheduler-task-kind-accrue
                                     :scheduler-task-kind-capitalize]))))
  (testing "no tasks allows nothing" (is (= #{} (SUT/job-allowed-periods [])))))

(deftest validate-periodicity-test
  (testing "allowed periodicity passes (nil)"
    (is (nil? (SUT/validate-periodicity [:scheduler-task-kind-capitalize]
                                        :scheduler-periodicity-monthly))))
  (testing "disallowed periodicity rejects"
    (let [result (SUT/validate-periodicity [:scheduler-task-kind-accrue
                                            :scheduler-task-kind-capitalize]
                                           :scheduler-periodicity-monthly)]
      (is (error/rejection? result))
      (is (= :scheduler/periodicity-not-allowed (error/kind result))))))

(deftest validate-run-time-test
  (testing "an hourly run time is the minute past the hour"
    (is (nil? (SUT/validate-run-time :scheduler-periodicity-hourly 59))))
  (testing "sixty minutes past the hour names no minute"
    (let [result (SUT/validate-run-time :scheduler-periodicity-hourly 60)]
      (is (error/rejection? result))
      (is (= :scheduler/run-time-not-allowed (error/kind result)))))
  (testing "a daily run time is minutes past midnight, under a day"
    (is (nil? (SUT/validate-run-time :scheduler-periodicity-daily 1439)))
    (is (error/rejection? (SUT/validate-run-time :scheduler-periodicity-daily
                                                 1440))))
  (testing "a negative or missing run time is refused"
    (is (error/rejection? (SUT/validate-run-time :scheduler-periodicity-daily
                                                 -1)))
    (is (error/rejection? (SUT/validate-run-time :scheduler-periodicity-daily
                                                 nil)))))

(deftest ->cron-test
  (testing "hourly fires every hour at that minute past it"
    (is (= "0 15 * * * ?" (SUT/->cron :scheduler-periodicity-hourly 15)))
    (is (= "0 0 * * * ?" (SUT/->cron :scheduler-periodicity-hourly 0))))
  (testing "run-time-minutes splits into hour/minute; 120 = 02:00"
    (is (= "0 0 2 * * ?" (SUT/->cron :scheduler-periodicity-daily 120))))
  (testing "monthly defaults to the 1st"
    (is (= "0 30 6 1 * ?" (SUT/->cron :scheduler-periodicity-monthly 390))))
  (testing "monthly first day is explicit day 1"
    (is (= "0 30 6 1 * ?"
           (SUT/->cron :scheduler-periodicity-monthly
                       390
                       :scheduler-monthly-day-first))))
  (testing "monthly last day uses the Quartz L token"
    (is (= "0 30 6 L * ?"
           (SUT/->cron :scheduler-periodicity-monthly
                       390
                       :scheduler-monthly-day-last))))
  (testing "an unknown monthly-day falls back to the 1st"
    (is (= "0 30 6 1 * ?"
           (SUT/->cron :scheduler-periodicity-monthly
                       390
                       :scheduler-monthly-day-unknown))))
  (testing "yearly fires on Jan 1"
    (is (= "0 0 0 1 1 ?" (SUT/->cron :scheduler-periodicity-yearly 0)))))

(deftest system?-test
  (testing "system kind is system"
    (is (SUT/system? {:kind :scheduler-job-kind-system})))
  (testing "user / unknown / absent kinds are not system"
    (is (not (SUT/system? {:kind :scheduler-job-kind-user})))
    (is (not (SUT/system? {:kind :scheduler-job-kind-unknown})))
    (is (not (SUT/system? {})))))

(deftest validate-system-edits-test
  (let [system {:job-id "account-migration" :kind :scheduler-job-kind-system}
        user {:job-id "daily-interest" :kind :scheduler-job-kind-user}]
    (testing "editing only the time of a system job is allowed (nil)"
      (is (nil? (SUT/validate-system-edits system {:run-time-minutes 300}))))
    (testing "a system job rejects cadence / enabled edits"
      (doseq [edit [{:periodicity :scheduler-periodicity-daily}
                    {:monthly-day :scheduler-monthly-day-first}
                    {:enabled false}]]
        (let [result (SUT/validate-system-edits system edit)]
          (is (error/rejection? result))
          (is (= :scheduler/system-job-locked (error/kind result))))))
    (testing "a user job allows any edit (nil)"
      (is (nil? (SUT/validate-system-edits user
                                           {:periodicity
                                            :scheduler-periodicity-monthly
                                            :enabled false}))))))

(deftest expected-end-at-test
  (testing "started-at plus the prior run's duration"
    (is (= 1150 (SUT/expected-end-at 1000 {:started-at 100 :finished-at 250}))))
  (testing "nil when the prior run never finished"
    (is (nil? (SUT/expected-end-at 1000 {:started-at 100})))
    (is (nil? (SUT/expected-end-at 1000 nil)))))

(deftest task-recording-test
  (let [started (SUT/started-task "accrue" 1000)]
    (testing "a task the run has reached is running, and stamped"
      (is (= {:label "accrue"
              :status :scheduler-task-status-running
              :started-at 1000}
             started)))
    (testing "finishing carries the counts the pass reported"
      ;; The pass counts accounts; the run records them as records,
      ;; because the scheduler has no business knowing what a pass
      ;; iterates over.
      (is (= {:label "accrue"
              :status :scheduler-task-status-succeeded
              :started-at 1000
              :finished-at 1600
              :records-processed 12480
              :records-failed 3}
             (SUT/finished-task started
                                1600
                                {:accounts-processed 12480
                                 :accounts-failed 3}))))
    (testing "a zero is a real count and is kept"
      (let [task (SUT/finished-task started
                                    1600
                                    {:accounts-processed 0 :accounts-failed 0})]
        (is (= 0 (:records-processed task)))
        (is (= 0 (:records-failed task)))))
    (testing "a task with nothing to count carries no counts at all"
      ;; The migration task reports its own shape and no account
      ;; figures — better absent than a zero it never meant.
      (let [task (SUT/finished-task started 1600 {:migrated 0})]
        (is (= :scheduler-task-status-succeeded (:status task)))
        (is (not (contains? task :records-processed)))
        (is (not (contains? task :records-failed)))))
    (testing "failing keeps the timings and the anomaly that stopped it"
      (let [task (SUT/failed-task started
                                  1600
                                  (error/reject :interest/missing-gl-account
                                                {:message "no such account"}))]
        (is (= :scheduler-task-status-failed (:status task)))
        (is (= 1600 (:finished-at task)))
        (is (string? (:error task)))))))

(deftest skipped-tasks-test
  (testing "tasks after a failure are recorded as skipped, in order"
    (is (= [{:label "capitalize" :status :scheduler-task-status-skipped}
            {:label "migrate" :status :scheduler-task-status-skipped}]
           (SUT/skipped-tasks ["capitalize" "migrate"]))))
  (testing "a skipped task carries no timings — the run never reached it"
    (let [[task] (SUT/skipped-tasks ["capitalize"])]
      (is (not (contains? task :started-at)))
      (is (not (contains? task :finished-at)))))
  (testing "nothing left to skip is an empty vector, not nil"
    (is (= [] (SUT/skipped-tasks [])))))
