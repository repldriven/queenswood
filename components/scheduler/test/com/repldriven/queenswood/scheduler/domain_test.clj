(ns com.repldriven.queenswood.scheduler.domain-test
  "Pure-function tests for the scheduler domain: what each task's
  cadence allows a schedule, what a system job's edits may change, the
  expected-end estimate, and the fire a run holds. No FDB, no
  scheduler."
  (:require
    [com.repldriven.queenswood.scheduler.domain :as SUT]

    [com.repldriven.mono.error.interface :as error]

    [clojure.test :refer [deftest is testing]]))

(def ^:private noon-on-the-fifth
  "2026-03-05T12:34:56Z, as epoch-ms."
  1772714096000)

(def ^:private daily "0 0 17 * * ?")

(defn- refusal
  [task-kinds schedule]
  (error/kind (SUT/validate-schedule task-kinds schedule noon-on-the-fifth)))

(deftest validate-schedule-test
  (testing "accrue runs exactly once a day"
    (is (nil? (SUT/validate-schedule [:scheduler-task-kind-accrue]
                                     daily
                                     noon-on-the-fifth)))
    (doseq [schedule ["0 0 * * * ?" "0 0 17 L * ?" "0 0 17 ? * MON-FRI"]]
      (is (= :scheduler/periodicity-not-allowed
             (refusal [:scheduler-task-kind-accrue] schedule))
          schedule)))
  (testing "capitalize runs no more than once a day"
    (doseq [schedule [daily "0 0 17 L * ?" "0 0 17 L 3,6,9,12 ?" "0 0 0 1 1 ?"
                      "0 30 2 ? * MON-FRI"]]
      (is (nil? (SUT/validate-schedule [:scheduler-task-kind-capitalize]
                                       schedule
                                       noon-on-the-fifth))
          schedule))
    (is (= :scheduler/periodicity-not-allowed
           (refusal [:scheduler-task-kind-capitalize] "0 0 * * * ?"))))
  (testing "a job's tasks each have their say: accrue narrows it to daily"
    (is (= :scheduler/periodicity-not-allowed
           (refusal [:scheduler-task-kind-accrue
                     :scheduler-task-kind-capitalize]
                    "0 0 17 L * ?"))))
  (testing "a schedule that will not parse, or never fires, is invalid"
    (is (= :scheduler/invalid-schedule
           (refusal [:scheduler-task-kind-capitalize] "every day")))
    (is (= :scheduler/invalid-schedule
           (refusal [:scheduler-task-kind-capitalize] "0 0 0 1 1 ? 2020")))))

(deftest system?-test
  (testing "system kind is system"
    (is (SUT/system? {:kind :scheduler-job-kind-system})))
  (testing "user / unknown / absent kinds are not system"
    (is (not (SUT/system? {:kind :scheduler-job-kind-user})))
    (is (not (SUT/system? {:kind :scheduler-job-kind-unknown})))
    (is (not (SUT/system? {})))))

(deftest validate-system-edits-test
  (let [system {:job-id "account-migration"
                :kind :scheduler-job-kind-system
                :schedule "0 0 0 * * ?"}
        user {:job-id "daily-interest"
              :kind :scheduler-job-kind-user
              :schedule daily}]
    (testing "moving only the time of a system job is allowed (nil)"
      (is (nil? (SUT/validate-system-edits system {:schedule "0 0 5 * * ?"}))))
    (testing "a system job rejects a change of days, or of status"
      (doseq [edit [{:schedule "0 0 0 1 * ?"}
                    {:status :scheduler-job-status-paused}]]
        (let [result (SUT/validate-system-edits system edit)]
          (is (error/rejection? result))
          (is (= :scheduler/system-job-locked (error/kind result))))))
    (testing "a user job allows any edit (nil)"
      (is (nil? (SUT/validate-system-edits user
                                           {:schedule "0 0 17 L * ?"
                                            :status
                                            :scheduler-job-status-paused}))))))

(deftest expected-end-at-test
  (testing "created-at plus the prior run's duration"
    (is (= 1150
           (SUT/expected-end-at 1000 {:created-at 100 :succeeded-at 250}))))
  (testing "nil when the prior run never succeeded"
    (is (nil? (SUT/expected-end-at 1000 {:created-at 100})))
    (is (nil? (SUT/expected-end-at 1000 nil)))))

(deftest task-recording-test
  (let [started (SUT/started-task "accrue" 1000)]
    (testing "a task the run has reached is running, and stamped"
      (is (= {:label "accrue"
              :status :scheduler-task-status-running
              :started-at 1000}
             started)))
    (testing "finishing carries the counts the pass reported"
      (is (= {:label "accrue"
              :status :scheduler-task-status-succeeded
              :started-at 1000
              :finished-at 1600
              :processed-count 12480
              :failed-count 3}
             (SUT/finished-task started
                                1600
                                {:accounts-processed 12480
                                 :accounts-failed 3}))))
    (testing "a zero is a real count and is kept"
      (let [task (SUT/finished-task started
                                    1600
                                    {:accounts-processed 0 :accounts-failed 0})]
        (is (= 0 (:processed-count task)))
        (is (= 0 (:failed-count task)))))
    (testing "a task with nothing to count carries no counts at all"
      ;; The migration task reports its own shape and no account
      ;; figures — better absent than a zero it never meant.
      (let [task (SUT/finished-task started 1600 {:migrated 0})]
        (is (= :scheduler-task-status-succeeded (:status task)))
        (is (not (contains? task :processed-count)))
        (is (not (contains? task :failed-count)))))
    (testing "failing keeps the timings and the anomaly that stopped it"
      (let [task (SUT/failed-task started
                                  1600
                                  (error/reject :interest/missing-gl-account
                                                {:message "no such account"}))]
        (is (= :scheduler-task-status-failed (:status task)))
        (is (= 1600 (:finished-at task)))
        (is (string? (:failure-reason task)))
        (is (not (contains? task :failed-count)))))
    (testing "an incomplete pass records the counts it carries"
      (let [task (SUT/failed-task started
                                  1600
                                  (error/fail :interest/run-incomplete
                                              {:message "accounts failed"
                                               :accounts-processed 5
                                               :accounts-failed 4}))]
        (is (= 5 (:processed-count task)))
        (is (= 4 (:failed-count task)))))))

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

(deftest period-refusal-test
  (let [job {:job-id "daily-interest" :schedule "0 0 0 * * ?"}
        hour (* 60 60 1000)
        run (fn [status created-at]
              {:run-id "run.1" :status status :created-at created-at})]
    (testing "a run that succeeded or is running this period refuses another"
      (is (= :scheduler/period-already-run
             (error/kind (SUT/period-refusal job
                                             [(run
                                               :scheduler-run-status-succeeded
                                               (- noon-on-the-fifth hour))]
                                             noon-on-the-fifth))))
      (is (= "The job is already running this period"
             (:message (error/payload (SUT/period-refusal
                                       job
                                       [(run :scheduler-run-status-running
                                             noon-on-the-fifth)]
                                       noon-on-the-fifth))))))
    (testing "a failed run leaves its period open"
      (is (nil? (SUT/period-refusal job
                                    [(run :scheduler-run-status-failed
                                          (- noon-on-the-fifth hour))]
                                    noon-on-the-fifth))))
    (testing "a run in an earlier period refuses nothing"
      (is (nil? (SUT/period-refusal job
                                    [(run :scheduler-run-status-succeeded
                                          (- noon-on-the-fifth (* 24 hour)))]
                                    noon-on-the-fifth))))
    (testing "a schedule firing twice a day has two periods a day"
      (is (nil? (SUT/period-refusal (assoc job :schedule "0 0 0,12 * * ?")
                                    [(run :scheduler-run-status-succeeded
                                          (- noon-on-the-fifth (* 2 hour)))]
                                    noon-on-the-fifth))))))
