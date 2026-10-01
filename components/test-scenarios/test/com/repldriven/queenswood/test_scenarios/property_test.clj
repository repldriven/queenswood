(ns com.repldriven.queenswood.test-scenarios.property-test
  "Fugato-driven model-equality property test. The same runner that
  drives EDN scenarios drives generated command sequences here. A trial
  holds when both standing invariants held after every step, no step
  timed out, and the model's end state projects equal to reality's. A
  failure is shrunk, then walked step by step to name the first step at
  which the model and reality differ."
  (:require
    [com.repldriven.queenswood.test-scenarios.interface :as SUT]
    [com.repldriven.queenswood.test-scenarios.rig :as rig]

    [com.repldriven.queenswood.test-model.interface :as model]

    [com.repldriven.mono.log.interface :as log]
    [com.repldriven.mono.test-system.interface :refer [with-test-system]]

    [fugato.core :as fugato]

    [clojure.test :refer [deftest is testing]]
    [clojure.test.check :as tc]
    [clojure.test.check.generators :as gen]
    [clojure.test.check.properties :as prop]))

(deftest model-generates-plausible-sequences-test
  (testing "fugato produces vectors of {:command :args} maps"
    (let [samples (gen/sample (fugato/commands model/model rig/model-init 3) 5)
          known (set (keys model/model))]
      (doseq [s samples]
        (is (>= (count s) 3))
        (doseq [c s]
          (is (contains? known (:command c)))
          (is (vector? (:args c))))))))

(defn- trial
  "Runs `cmds` against reality and the model, and returns why the trial
  fails, or nil when it holds."
  [bank cmds]
  (let [final (SUT/run-commands (SUT/fresh-context bank
                                                   {}
                                                   {:model-init rig/model-init})
                                cmds)
        {:keys [invariant-failures runner-errors]} final]
    (cond
     (seq runner-errors)
     {:runner-errors runner-errors}

     (seq invariant-failures)
     {:invariant-failures invariant-failures}

     :else
     (let [expected (SUT/projected-model
                     (fugato/execute model/model rig/model-init cmds))
           actual (SUT/projected-real final)]
       (when-not (= expected actual)
         {:end-states-differ true})))))

(defn- record-trial
  [stats cmds]
  (-> stats
      (update :trials inc)
      (update :total-commands + (count cmds))
      (update :by-command
              (fn [m]
                (reduce (fn [acc c] (update acc (:command c) (fnil inc 0)))
                        m
                        cmds)))
      (update :lengths conj (count cmds))))

(defn- summarise
  [{:keys [trials total-commands by-command lengths]}]
  (let [n (max 1 trials)]
    (log/info "model-eq-reality summary"
              {:trials trials
               :total-commands total-commands
               :sequence-length (when (seq lengths)
                                  {:min (apply min lengths)
                                   :max (apply max lengths)
                                   :avg (double (/ (reduce + lengths) n))})
               :by-command (into (sorted-map)
                                 (map (fn [[cmd cnt]]
                                        [cmd
                                         {:count cnt
                                          :avg-per-trial (double
                                                          (/ cnt n))}]))
                                 by-command)})))

(def ^:private num-tests 50)
(def ^:private max-size 30)

(deftest model-eq-reality
  ;; One FDB container serves all trials; each trial runs on a fresh
  ;; runner context, with its own id mapping and `:run-id`, so the
  ;; accounts earlier trials left in the bank are invisible to it.
  (with-test-system
   [sys [rig/config-file rig/patch-handlers]]
   (let [bank (rig/bank sys)
         stats (atom {:trials 0 :total-commands 0 :by-command {} :lengths []})
         result (tc/quick-check num-tests
                                (prop/for-all [cmds
                                               (fugato/commands model/model
                                                                rig/model-init)]
                                              (swap! stats record-trial cmds)
                                              (nil? (trial bank cmds)))
                                :max-size
                                max-size)]
     (summarise @stats)
     (when-not (:pass? result)
       (let [smallest (get-in result [:shrunk :smallest 0])
             why (trial bank smallest)
             walked (SUT/first-divergence
                     (SUT/fresh-context bank {} {:model-init rig/model-init})
                     smallest)]
         (is (:pass? result)
             (str "shrunk to " (pr-str smallest)
                  "\n  why: " (pr-str why)
                  "\n  first divergence: " (pr-str (:divergence walked))))))
     (is (:pass? result)))))
