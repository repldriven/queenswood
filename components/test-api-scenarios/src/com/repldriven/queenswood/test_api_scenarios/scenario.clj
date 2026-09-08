(ns com.repldriven.queenswood.test-api-scenarios.scenario
  (:require
    [com.repldriven.mono.error.interface :as error]

    [malli.core :as m]
    [malli.error :as me]

    [clojure.edn :as edn]
    [clojure.java.io :as io]))

(def step
  [:map
   [:command keyword?]
   [:request {:optional true} map?]
   [:assert {:optional true} map?]
   ;; `:api/race` only — how many copies of `:request` to send at once.
   [:count {:optional true} pos-int?]
   [:as {:optional true} keyword?]])

(def schema
  [:map
   [:name string?]
   [:tags {:optional true} [:set keyword?]]
   [:given {:optional true} [:vector step]]
   [:when [:vector step]]
   [:then {:optional true} [:vector step]]])

(defn steps
  [scenario]
  (let [{:keys [given then]} scenario
        when-steps (:when scenario)]
    (vec (concat given when-steps then))))

(defn from-resource
  [resource-path]
  (error/let-nom>
    [src (or (io/resource resource-path)
             (error/fail :bank-test-api-scenarios/scenario
                         {:message "Scenario resource not found"
                          :resource resource-path}))
     parsed (error/try-nom :bank-test-api-scenarios/scenario
                           "Failed to parse scenario EDN"
                           (edn/read-string (slurp src)))
     _ (when-not (m/validate schema parsed)
         (error/fail :bank-test-api-scenarios/scenario
                     {:message "Scenario failed schema validation"
                      :resource resource-path
                      :explain (me/humanize
                                (m/explain schema parsed))}))]
    parsed))
