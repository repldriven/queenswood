(ns com.repldriven.queenswood.test-scenarios.property-test
  "Fugato-driven model-equality property test. The same runner that
  drives EDN scenarios drives generated command sequences here; on
  each trial, the model end-state and the projected real-system end-
  state must agree."
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
    [com.repldriven.mono.test-system.interface :refer [with-test-system]]

    [fugato.core :as fugato]

    [clojure.test :refer [deftest is testing]]
    [clojure.test.check :as tc]
    [clojure.test.check.generators :as gen]
    [clojure.test.check.properties :as prop]))

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
   :bus (system/instance sys [:message-bus :bus])
   :schemas (system/instance sys [:avro :serde])
   :scheme-payment-command-channel :schemes-payment-command})

(deftest model-generates-plausible-sequences-test
  (testing "fugato produces vectors of {:command :args} maps"
    (let [samples (gen/sample (fugato/commands model/model model/init-state 3)
                              5)
          known (set (keys model/model))]
      (doseq [s samples]
        (is (>= (count s) 3))
        (doseq [c s]
          (is (contains? known (:command c)))
          (is (vector? (:args c))))))))

(defn- enrich-with-bank-real-id
  "Walks `:products` / `:parties` in ctx and tacks the owning bank's
  real-id on each, so a projection that reads from the real bank
  can resolve `(get-product bank-id prod-id)` etc. without re-doing
  the lookup. Returns `{model-id {:real-id ... :bank-real-id ...}}`."
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
                         (:payments ctx))
     :inbound-payments (projections/project-inbound-payments
                        bank
                        (:run-id ctx)
                        (set (map (fn [n] (keyword (str "in-" n)))
                                  (range (:next-inbound-id ctx)))))}))

(defn- project-model
  [model-state]
  {:balances (projections/project-model-balances model-state)
   :products (projections/project-model-products model-state)
   :parties (projections/project-model-parties model-state)
   :banks (projections/project-model-banks model-state)
   :accounts (projections/project-model-accounts model-state)
   :transactions (projections/project-model-transactions model-state)
   :outbound-payments (projections/project-model-outbound-payments
                       model-state)
   :inbound-payments (projections/project-model-inbound-payments
                      model-state)})

(defn- run-and-compare
  "Drives `cmds` through both reality and the model, then compares
  projected state across balances, products, and parties. Returns
  true on agreement; false (with the diff logged) on divergence."
  [bank cmds]
  (let [ctx (SUT/fresh-context bank)
        final (SUT/run-commands ctx cmds)
        real (project-real bank final)
        model-end (fugato/execute model/model model/init-state cmds)
        expected (project-model model-end)
        ok (= expected real)]
    (when-not ok
      (log/error "model-eq-reality divergence"
                 {:commands cmds :expected expected :real real}))
    ok))

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
  ;; One FDB container serves all trials; isolation comes from per-
  ;; trial fresh runner contexts (own id-mapping, own `:run-id` salt
  ;; for idempotency keys). The model resets per trial via
  ;; `fugato/execute` reducing from `init-state`; the bank accumulates
  ;; accounts across trials but the projection is keyed by the trial's
  ;; id-mapping so prior trials' accounts are invisible.
  (with-test-system
   [sys ["classpath:test-scenarios/application-test.yml" patch-handlers]]
   (let [bank (fdb-config sys)
         stats (atom {:trials 0 :total-commands 0 :by-command {} :lengths []})
         _ (log/info "model-eq-reality starting"
                     {:num-tests num-tests :max-size max-size})
         result (tc/quick-check
                 num-tests
                 (prop/for-all [cmds
                                (fugato/commands model/model model/init-state)]
                               (swap! stats record-trial cmds)
                               (run-and-compare bank cmds))
                 :max-size
                 max-size)]
     (summarise @stats)
     (is (:result result) (str "shrunk failure: " (pr-str result))))))
