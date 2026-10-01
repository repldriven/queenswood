(ns com.repldriven.queenswood.test-scenarios.runner
  (:require
    [com.repldriven.queenswood.test-scenarios.await :as await]
    [com.repldriven.queenswood.test-scenarios.id-mapping :as id-mapping]
    [com.repldriven.queenswood.test-scenarios.invariants :as invariants]
    [com.repldriven.queenswood.test-scenarios.scenario :as scenario]
    [com.repldriven.queenswood.test-scenarios.verbs :as verbs]

    [com.repldriven.mono.identity-provider.interface :as identity-provider]
    [com.repldriven.mono.utility.interface :as util]))

(defn fresh-context
  [bank {:keys [scheme-commands dead-letters envelope-schemas]}
   {:keys [await-timeout-ms]}]
  {:bank bank
   :scheme-commands scheme-commands
   :dead-letters dead-letters
   :envelope-schemas envelope-schemas
   :await-timeout-ms (or await-timeout-ms await/default-timeout-ms)
   :identity-provider (identity-provider/local-provider {})
   :id-mapping id-mapping/init
   :banks {}
   :products {}
   :parties {}
   :accounts {}
   :payments {}
   :next-model-id 0
   :next-bank-id 0
   :next-product-id 0
   :next-party-id 0
   :next-payment-id 0
   :next-inbound-id 0
   :run-id (str (util/uuidv7))
   :counter 0
   :outcomes []})

(defn- changes-state?
  [command]
  (contains? #{:model :fixture :reality} (scenario/kind command)))

(defn run-step
  [ctx step]
  (let [index (:step-index ctx 0)
        ctx' (assoc (verbs/dispatch ctx step) :step-index (inc index))
        failures (when (changes-state? (:command step))
                   (invariants/check ctx'))]
    (cond-> ctx'
            (seq failures)
            (update :invariant-failures
                    (fnil conj [])
                    {:index index
                     :command (:command step)
                     :failures failures}))))

(defn stopped?
  [ctx]
  (boolean (seq (:runner-errors ctx))))

(defn run-commands
  [ctx commands]
  (reduce (fn [ctx command]
            (let [ctx' (run-step ctx command)]
              (if (stopped? ctx') (reduced ctx') ctx')))
          ctx
          commands))
