(ns com.repldriven.queenswood.modulr-simulator.system
  (:require
    [com.repldriven.queenswood.modulr-simulator.ledger :as ledger]

    [com.repldriven.mono.system.interface :as system]))

(system/defcomponents :modulr-simulator
                      {:state {:system/start (fn [{:system/keys [instance]}]
                                               (or instance
                                                   (atom (ledger/empty-state))))
                               :system/instance-schema some?}})
