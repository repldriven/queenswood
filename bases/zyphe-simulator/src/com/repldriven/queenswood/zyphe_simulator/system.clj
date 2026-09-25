(ns com.repldriven.queenswood.zyphe-simulator.system
  (:require
    [com.repldriven.mono.system.interface :as system]))

(system/defcomponents
 :zyphe-simulator
 {:state {:system/start (fn [{:system/keys [instance]}]
                          (or instance (atom {:verification-requests {}})))
          :system/instance-schema some?}})
