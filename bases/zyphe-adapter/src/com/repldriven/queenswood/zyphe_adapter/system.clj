(ns com.repldriven.queenswood.zyphe-adapter.system
  (:require
    [com.repldriven.queenswood.zyphe-adapter.commands :as commands]

    [com.repldriven.mono.system.interface :as system]))

(def ^:private command-processor
  {:system/start (fn [{:system/keys [config instance]}]
                   (or instance (commands/->ZypheCommandProcessor config)))
   :system/config {:schemas system/required-component
                   :record-db system/required-component
                   :record-store system/required-component}
   :system/instance-schema some?})

(system/defcomponents :zyphe-adapter {:command-processor command-processor})
