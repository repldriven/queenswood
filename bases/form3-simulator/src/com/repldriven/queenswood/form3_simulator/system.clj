(ns com.repldriven.queenswood.form3-simulator.system
  (:require
    [com.repldriven.queenswood.form3-simulator.records :as records]

    [com.repldriven.mono.system.interface :as system]))

(system/defcomponents :form3-simulator
                      {:state {:system/start
                               (fn [{:system/keys [instance]}]
                                 (or instance (atom (records/empty-state))))
                               :system/instance-schema some?}})
