(ns com.repldriven.queenswood.test-scenarios.divergence
  (:require
    [com.repldriven.queenswood.test-scenarios.projection :as projection]
    [com.repldriven.queenswood.test-scenarios.runner :as runner]
    [com.repldriven.queenswood.test-scenarios.scenario :as scenario]

    [com.repldriven.queenswood.test-model.interface :as model]

    [fugato.core :as fugato]

    [clojure.data :as data]))

(defn- advance
  [state step]
  (if (contains? #{:model :fixture} (scenario/kind (:command step)))
    (fugato/execute model/model state [step])
    state))

(defn walk
  ([ctx steps] (walk ctx steps (:model-init ctx)))
  ([ctx steps init-state]
   (loop [ctx ctx
          state init-state
          index 0
          remaining steps]
     (if-let [step (first remaining)]
       (let [ctx' (runner/run-step ctx step)
             state' (advance state step)]
         (if (runner/stopped? ctx')
           {:ctx ctx' :model state'}
           (let [expected (projection/model state')
                 actual (projection/real (:bank ctx') ctx')]
             (if (= expected actual)
               (recur ctx' state' (inc index) (rest remaining))
               (let [[only-model only-reality] (data/diff expected actual)]
                 {:ctx ctx'
                  :model state'
                  :divergence {:index index
                               :step step
                               :only-model only-model
                               :only-reality only-reality}})))))
       {:ctx ctx :model state}))))
