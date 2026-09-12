(ns com.repldriven.queenswood.test-scenarios.interface-test
  (:require
    [com.repldriven.queenswood.test-scenarios.system]

    [com.repldriven.queenswood.test-scenarios.interface :as SUT]

    [com.repldriven.queenswood.clearbank-adapter.interface :as cb-adapter]
    [com.repldriven.queenswood.clearbank-simulator.interface :as cb-simulator]
    [com.repldriven.queenswood.onfido-adapter.interface :as onfido-adapter]
    [com.repldriven.queenswood.onfido-simulator.interface :as onfido-simulator]
    [com.repldriven.queenswood.test-model.interface :as model]
    [com.repldriven.queenswood.test-projections.interface :as projections]

    [com.repldriven.mono.log.interface :as log]
    [com.repldriven.mono.system.interface :as system]
    [com.repldriven.mono.test-system.interface :refer
     [with-test-system nom-test>]]

    [fugato.core :as fugato]

    [clojure.java.io :as io]
    [clojure.test :refer [deftest is testing]]))

(defn- patch-handlers
  [defs]
  (-> defs
      (assoc-in [:system/defs :clearbank-simulator-server :handler]
                cb-simulator/app)
      (assoc-in [:system/defs :clearbank-adapter-server :handler]
                cb-adapter/app)
      (assoc-in [:system/defs :onfido-simulator-server :handler]
                onfido-simulator/app)
      (assoc-in [:system/defs :onfido-adapter-server :handler]
                onfido-adapter/app)))

(defn- fdb-config
  [sys]
  {:record-db (system/instance sys [:fdb :record-db])
   :record-store (system/instance sys [:fdb :store])
   ;; `payment/submit-outbound` publishes a schemes-payment-command
   ;; on the bus when the outbound is created — wire the bus,
   ;; schemas, and channel keyword through so it lands on the right
   ;; topic. The clearbank-adapter command-processor consumes it,
   ;; ClearBank settles, and bank-payment's event-processor on
   ;; schemes-payments-event credits the inbound side.
   :bus (system/instance sys [:message-bus :bus])
   :schemas (system/instance sys [:avro :serde])
   :scheme-payment-command-channel :schemes-payment-command})


(defn- scenario-files
  []
  (->> (io/file (.getFile (io/resource "test-scenarios/scenarios")))
       (.listFiles)
       (filter (fn [f] (.endsWith (.getName f) ".edn")))
       (sort-by (fn [f] (.getName f)))))

(defn- enrich-with-bank-real-id
  [model->real banks]
  (->> model->real
       (map (fn [[model-id {:keys [real-id bank]}]]
              [model-id
               {:real-id real-id
                :bank-real-id (get-in banks [bank :real-id])}]))
       (into {})))

(defn- project-real
  [bank ctx]
  (let [real->model (get-in ctx [:id-mapping :real->model])]
    {:balances (projections/project-balances
                bank
                (projections/real->bank (:accounts ctx)
                                        (:banks ctx)
                                        (get-in ctx [:id-mapping :model->real]))
                real->model)
     :products (projections/project-products
                bank
                (enrich-with-bank-real-id (:products ctx) (:banks ctx)))
     :parties (projections/project-parties
               bank
               (enrich-with-bank-real-id (:parties ctx) (:banks ctx)))
     :banks (projections/project-banks bank ctx)
     :accounts (projections/project-accounts bank ctx)
     :transactions (projections/project-transactions bank real->model)
     :outbound-payments (projections/project-outbound-payments
                         bank
                         (:payments ctx))}))

(defn- project-model
  [model-state]
  {:balances (projections/project-model-balances model-state)
   :products (projections/project-model-products model-state)
   :parties (projections/project-model-parties model-state)
   :banks (projections/project-model-banks model-state)
   :accounts (projections/project-model-accounts model-state)
   :transactions (projections/project-model-transactions model-state)
   :outbound-payments (projections/project-model-outbound-payments
                       model-state)})

(def ^:private assertion-verbs
  #{:assert-balance :assert-outcome :assert-no-anomaly :assert-rejection-kind})

(defn- run-with-model-check
  "Folds `steps` through the runner *and* the model in lock-step.
  After every modelled step, projects both ends and asserts they
  agree. Assertion verbs (`:assert-*`) are neutral wrt the model.
  Any other command not in the model flips `tracking?` off — real
  state has advanced in ways the model doesn't see, so further
  model comparisons would diverge for reasons unrelated to bugs.
  The runner keeps going either way so the scenario's
  hand-computed `:assert-balance` calls still run.

  Returns `{:ctx :model-eq-checks :modelled :asserts :unmodelled
            :tracking-cut-off?}` so the caller can log a per-scenario
  summary."
  [scenario-name bank steps]
  (loop [ctx (SUT/fresh-context bank)
         model-state model/init-state
         remaining steps
         tracking? true
         stats {:model-eq-checks 0
                :modelled 0
                :asserts 0
                :unmodelled 0
                :tracking-cut-off-at nil}]
    (if-let [step (first remaining)]
      (let [cmd (:command step)
            ctx' (SUT/run-commands ctx [step])
            assertion? (contains? assertion-verbs cmd)
            modelled? (contains? model/model cmd)
            tracking-after? (and tracking? (or assertion? modelled?))
            stats' (-> stats
                       (update (cond assertion?
                                     :asserts
                                     modelled?
                                     :modelled
                                     :else
                                     :unmodelled)
                               inc)
                       (cond->
                        (and tracking? (not tracking-after?))
                        (assoc :tracking-cut-off-at cmd)))]
        (if (and tracking? modelled?)
          (let [model-state' (fugato/execute model/model model-state [step])
                real (project-real bank ctx')
                expected (project-model model-state')]
            (is (= expected real)
                (str scenario-name " — model-eq after " cmd))
            (recur ctx'
                   model-state'
                   (rest remaining)
                   tracking-after?
                   (update stats' :model-eq-checks inc)))
          (recur ctx' model-state (rest remaining) tracking-after? stats')))
      (assoc stats :ctx ctx))))

(deftest scenarios-test
  ;; One test system serves every scenario. Per-scenario isolation
  ;; comes from `fresh-context` (own id-mapping, fresh `:run-id`
  ;; salting idempotency keys) and per-scenario projections (keyed
  ;; on the scenario's own model→real map, so prior scenarios'
  ;; records are invisible). Sharing the boot drops total scenario
  ;; runtime by ~6s × N scenarios.
  (let [files (scenario-files)]
    (is (seq files) "expected scenarios on the classpath")
    (log/info "scenarios starting" {:count (count files)})
    (with-test-system
     [sys ["classpath:test-scenarios/application-test.yml" patch-handlers]]
     (doseq [f files]
       (let [resource-path (str "test-scenarios/scenarios/" (.getName f))]
         (nom-test> [loaded (SUT/from-resource resource-path)
                     steps (SUT/steps loaded)
                     _ (log/info "scenario running"
                                 {:file (.getName f)
                                  :name (:name loaded)
                                  :steps (count steps)})
                     stats (testing (:name loaded)
                             (run-with-model-check (:name loaded)
                                                   (fdb-config sys)
                                                   steps))
                     _ (log/info "scenario complete"
                                 {:file (.getName f)
                                  :model-eq-checks (:model-eq-checks stats)
                                  :modelled (:modelled stats)
                                  :asserts (:asserts stats)
                                  :unmodelled (:unmodelled stats)
                                  :tracking-cut-off-at (:tracking-cut-off-at
                                                        stats)})]))))))
