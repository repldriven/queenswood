(ns com.repldriven.queenswood.test-scenarios.policies
  (:require
    [com.repldriven.queenswood.test-scenarios.verbs :as verbs]

    [com.repldriven.queenswood.test-model.interface :as model]

    [com.repldriven.mono.env.interface :as env]
    [com.repldriven.mono.error.interface :as error]))

(defn model-init
  [config-file]
  (error/let-nom>
    [config (env/config config-file :test)
     policies (or (get-in config [:system :policies])
                  (error/fail :test-scenarios/policies
                              {:message "The rig declares no policies"
                               :config-file config-file}))]
    (model/with-policies model/init-state
                         {:platform (get-in policies [:platform :policy])
                          :tier (get-in policies
                                        [(keyword verbs/bank-tier) :policy])})))
