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
    [com.repldriven.mono.system.interface :as system]
    [com.repldriven.mono.test-system.interface :refer
     [with-test-system nom-test>]]
    [com.repldriven.mono.utility.interface :as utility]

    [clojure.test :refer [deftest is testing]]))

(def ^:private config-file "classpath:scheduler/application-test.yml")

(def ^:private operator
  {:kind :actor-kind-operator :principal-id "queenswood-admin"})

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
                                      :schedule "0 0 0 * * ?"
                                      :status :scheduler-job-status-active
                                      :kind :scheduler-job-kind-system
                                      :created-at now})
                   _ (SUT/reconcile! config)
                   seeded (store/get-job config bank-id "daily-interest")
                   _ (is (= "0 0 17 * * ?" (:schedule seeded)))
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
                 _
                 (testing "an edit made as the API makes it, with no scheduler"
                   (nom-test> [_ (SUT/update-schedule api-config
                                                      bank-id
                                                      "daily-interest"
                                                      {:schedule "0 0 5 * * ?"}
                                                      operator)
                               _ (is (= "0 0 17 * * ?"
                                        (registered config
                                                    bank-id
                                                    "daily-interest")))]))
                 _ (testing "reaches the live trigger at the next reconcile"
                     (SUT/reconcile! config)
                     (is (= "0 0 5 * * ?"
                            (registered config bank-id "daily-interest"))))
                 _ (testing "and pausing it removes the trigger"
                     (nom-test> [_ (SUT/update-schedule
                                    api-config
                                    bank-id
                                    "daily-interest"
                                    {:status :scheduler-job-status-paused}
                                    operator)
                                 _ (SUT/reconcile! config)
                                 _ (is (nil? (registered config
                                                         bank-id
                                                         "daily-interest")))]))
                 _ (testing "while an hourly cadence is refused for its tasks"
                     (let [result (SUT/update-schedule api-config
                                                       bank-id
                                                       "daily-interest"
                                                       {:schedule "0 5 * * * ?"}
                                                       operator)]
                       (is (= :scheduler/periodicity-not-allowed
                              (error/kind result)))))]))))
