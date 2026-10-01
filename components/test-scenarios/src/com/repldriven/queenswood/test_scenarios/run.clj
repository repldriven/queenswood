(ns com.repldriven.queenswood.test-scenarios.run
  (:require
    [com.repldriven.queenswood.test-scenarios.divergence :as divergence]
    [com.repldriven.queenswood.test-scenarios.projection :as projection]
    [com.repldriven.queenswood.test-scenarios.runner :as runner]
    [com.repldriven.queenswood.test-scenarios.scenario :as scenario]

    [com.repldriven.queenswood.test-model.interface :as model]

    [fugato.core :as fugato]))

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

(defn trial-failure
  [ctx commands]
  (let [final (runner/run-commands ctx commands)
        {:keys [invariant-failures runner-errors]} final]
    (cond
     (seq runner-errors)
     {:runner-errors runner-errors}

     (seq invariant-failures)
     {:invariant-failures invariant-failures}

     (not= (projection/model (fugato/execute model/model
                                             (:model-init ctx)
                                             commands))
           (projection/real (:bank final) final))
     {:end-states-differ true})))
