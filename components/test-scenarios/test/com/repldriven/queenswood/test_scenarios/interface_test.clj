(ns com.repldriven.queenswood.test-scenarios.interface-test
  (:require
    [com.repldriven.queenswood.test-scenarios.interface :as SUT]
    [com.repldriven.queenswood.test-scenarios.rig :as rig]

    [com.repldriven.queenswood.balance.interface :as balances]

    [com.repldriven.mono.error.interface :as error]
    [com.repldriven.mono.log.interface :as log]
    [com.repldriven.mono.test-system.interface :refer [with-test-system]]

    [clojure.test :refer [deftest is testing]]))

(defn- check-report
  [{:keys [divergence invariant-failures runner-errors]}]
  (is (nil? divergence)
      (str "the model and reality diverge at step "
           (:index divergence)
           " "
           (pr-str (:step divergence))
           "\n  only in the model: "
           (pr-str (:only-model divergence))
           "\n  only in reality: "
           (pr-str (:only-reality divergence))))
  (is (empty? invariant-failures) (pr-str invariant-failures))
  (is (empty? runner-errors) (str "runner error " (pr-str runner-errors))))

(deftest scenarios-test
  ;; One test system serves every scenario. Each runs on a fresh context,
  ;; with its own banks and its own `:run-id` salting idempotency keys,
  ;; and projects only the records its own id mapping names.
  (let [files (SUT/scenario-files)]
    (is (seq files) "expected scenarios on the classpath")
    (with-test-system
     [sys [rig/config-file rig/patch-handlers]]
     (let [observers (rig/start-observers sys)]
       (try (doseq [{:keys [relative]} files]
              (let [loaded (SUT/from-resource (SUT/scenario-resource relative))]
                (testing relative
                  (if (error/anomaly? loaded)
                    (is (not (error/anomaly? loaded)) (pr-str loaded))
                    (let [report (SUT/run-scenario (SUT/fresh-context
                                                    (rig/bank sys)
                                                    observers
                                                    {:model-init
                                                     rig/model-init})
                                                   loaded)]
                      (log/info "scenario complete"
                                {:file relative
                                 :compared? (:compared? report)
                                 :steps (:step-index (:ctx report))})
                      (check-report report))))))
            (finally (rig/stop-observers observers)))))))

(deftest a-divergence-names-its-first-step-test
  (with-test-system
   [sys [rig/config-file rig/patch-handlers]]
   (testing "a model that numbers its accounts from 5 differs after step 0"
     (let [steps [{:command :create-bank :args []}
                  {:command :inbound-transfer :args [:acct-0 100]}]
           {:keys [divergence]} (SUT/first-divergence
                                 (SUT/fresh-context (rig/bank sys))
                                 steps
                                 (assoc rig/model-init :next-id 5))]
       (is (= 0 (:index divergence)))
       (is (= (first steps) (:step divergence)))
       (is (contains? (:balances (:only-model divergence)) :acct-5))
       (is (contains? (:balances (:only-reality divergence)) :acct-0))))))

(deftest a-trial-fails-on-a-timeout-or-a-broken-invariant-test
  (with-test-system
   [sys [rig/config-file rig/patch-handlers]]
   (let [bank (rig/bank sys)
         fresh (fn [opts]
                 (SUT/fresh-context bank
                                    {}
                                    (merge {:model-init rig/model-init} opts)))
         create-bank [{:command :create-bank :args []}]]
     (testing "a step that times out fails the trial as a runner error"
       (is (seq (:runner-errors (SUT/trial-failure (fresh {:await-timeout-ms 1})
                                                   create-bank)))))
     (testing "books a step leaves untied fail the trial"
       (let [ctx (SUT/run-commands (fresh {}) create-bank)
             bank-id (get-in ctx [:banks :bank-0 :real-id])
             acct-id (get-in ctx [:id-mapping :model->real :acct-0])]
         (is (nil? (balances/apply-legs bank
                                        bank-id
                                        [{:account-id acct-id
                                          :product-type
                                          :product-type-sub-ledger-current
                                          :balance-type :balance-type-default
                                          :balance-status :balance-status-posted
                                          :currency "GBP"
                                          :side :leg-side-credit
                                          :amount 100}]
                                        :transaction-type-fee)))
         (is (seq (:invariant-failures (SUT/trial-failure
                                        ctx
                                        [{:command :inbound-transfer
                                          :args [:acct-0 100]}])))))))))
