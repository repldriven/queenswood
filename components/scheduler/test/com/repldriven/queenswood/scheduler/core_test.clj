(ns com.repldriven.queenswood.scheduler.core-test
  "The runner against a live scheduler and FDB: the rig's scheduler
  fires, and reconcile makes the triggers match the rows however the
  rows were written."
  (:require
    [com.repldriven.queenswood.fdb.interface]
    [com.repldriven.queenswood.testcontainers.interface]

    [com.repldriven.queenswood.scheduler.core :as SUT]
    [com.repldriven.queenswood.scheduler.interface]
    [com.repldriven.queenswood.scheduler.store :as store]

    [com.repldriven.mono.error.interface :as error]
    [com.repldriven.mono.scheduler.interface :as scheduler]
    [com.repldriven.mono.system.interface :as system]
    [com.repldriven.mono.test-system.interface :refer
     [with-test-system nom-test>]]
    [com.repldriven.mono.utility.interface :as utility]

    [clojure.test :refer [deftest is testing]]))

(def ^:private config-file "classpath:scheduler/application-test.yml")

(defn- runner
  "The runner's instance: the config with the scheduler and the
  triggers it registered."
  [sys]
  (system/instance sys [:scheduler :runner]))

(defn- registered
  [config bank-id job-id]
  (get @(:triggers config) (str bank-id "/" job-id)))

(defn- seed
  [config bank-id]
  (store/transact config
                  (fn [txn] (SUT/seed-jobs txn bank-id))
                  :test/seed
                  "Failed to seed the jobs"))

(deftest the-rig-s-scheduler-fires-test
  (with-test-system
   [sys config-file]
   (let [sched (:scheduler (runner sys))
         fired (promise)]
     (testing "a trigger registered on the rig's scheduler fires"
       (is
        (=
         "tick"
         (scheduler/schedule sched "tick" "* * * * * ?" #(deliver fired true))))
       (is (true? (deref fired 3000 false)))
       (is (nil? (scheduler/unschedule sched "tick")))))))

(deftest reconcile-registers-a-bank-seeded-after-start-test
  (with-test-system
   [sys config-file]
   (let [config (runner sys)
         bank-id (str "bnk.core." (utility/uuidv7))]
     (testing "the runner started with no jobs for this bank"
       (is (nil? (registered config bank-id "daily-interest"))))
     (testing "a bank seeded after start is registered by the next reconcile"
       (nom-test> [_ (seed config bank-id)
                   _ (SUT/reconcile! config)
                   _ (is (= "0 0 17 * * ?"
                            (registered config bank-id "daily-interest")))
                   _ (is (= "0 0 0 * * ?"
                            (registered config bank-id "account-migration")))]))
     (testing "and a second reconcile changes nothing"
       (let [before @(:triggers config)]
         (SUT/reconcile! config)
         (is (= before @(:triggers config))))))))

(deftest reconcile-seeds-a-job-added-after-the-bank-test
  (with-test-system
   [sys config-file]
   (let [config (runner sys)
         bank-id (str "bnk.core." (utility/uuidv7))
         now (utility/now)]
     (testing "a bank with only the migration job gets the interest job seeded"
       (nom-test> [_ (store/save-job config
                                     {:bank-id bank-id
                                      :job-id "account-migration"
                                      :name "Account migration"
                                      :task-kinds
                                      [:scheduler-task-kind-account-migration]
                                      :periodicity :scheduler-periodicity-daily
                                      :run-time-minutes 0
                                      :enabled true
                                      :kind :scheduler-job-kind-system
                                      :created-at now
                                      :updated-at now})
                   _ (SUT/reconcile! config)
                   seeded (store/get-job config bank-id "daily-interest")
                   _ (is (= 1020 (:run-time-minutes seeded)))
                   _ (is (= "0 0 17 * * ?"
                            (registered config bank-id "daily-interest")))])))))

(deftest reconcile-follows-an-edit-made-without-a-scheduler-test
  (with-test-system
   [sys config-file]
   (let [config (runner sys)
         api-config (dissoc config :scheduler :triggers)
         bank-id (str "bnk.core." (utility/uuidv7))]
     (nom-test> [_ (seed config bank-id)
                 _ (SUT/reconcile! config)
                 _ (testing
                     "an edit made as the API makes it, with no scheduler"
                     (nom-test> [_ (SUT/update-schedule api-config
                                                        bank-id
                                                        "daily-interest"
                                                        {:run-time-minutes 300})
                                 _ (is (= "0 0 17 * * ?"
                                          (registered config
                                                      bank-id
                                                      "daily-interest")))]))
                 _ (testing "reaches the live trigger at the next reconcile"
                     (SUT/reconcile! config)
                     (is (= "0 0 5 * * ?"
                            (registered config bank-id "daily-interest"))))
                 _ (testing "and disabling it removes the trigger"
                     (nom-test> [_ (SUT/update-schedule api-config
                                                        bank-id
                                                        "daily-interest"
                                                        {:enabled false})
                                 _ (SUT/reconcile! config)
                                 _ (is (nil? (registered config
                                                         bank-id
                                                         "daily-interest")))]))
                 _ (testing "while an hourly cadence is refused for its tasks"
                     (let [result (SUT/update-schedule
                                   api-config
                                   bank-id
                                   "daily-interest"
                                   {:periodicity :scheduler-periodicity-hourly
                                    :run-time-minutes 5})]
                       (is (= :scheduler/periodicity-not-allowed
                              (error/kind result)))))]))))
