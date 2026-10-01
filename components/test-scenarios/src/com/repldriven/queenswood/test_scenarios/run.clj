(ns com.repldriven.queenswood.test-scenarios.run
  (:require
    [com.repldriven.queenswood.test-scenarios.divergence :as divergence]
    [com.repldriven.queenswood.test-scenarios.runner :as runner]
    [com.repldriven.queenswood.test-scenarios.scenario :as scenario]))

(defn run-scenario
  [ctx loaded]
  (let [compared? (scenario/compared? loaded)
        steps (scenario/steps loaded)
        {:keys [ctx divergence]} (if compared?
                                   (divergence/walk ctx steps)
                                   {:ctx (runner/run-commands ctx steps)})]
    {:ctx ctx
     :compared? compared?
     :divergence divergence
     :invariant-failures (:invariant-failures ctx)
     :runner-errors (:runner-errors ctx)}))
