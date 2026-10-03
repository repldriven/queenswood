(ns com.repldriven.queenswood.onfido-adapter.system
  (:require
    [com.repldriven.queenswood.onfido-adapter.commands :as commands]
    [com.repldriven.queenswood.onfido-adapter.subscriptions]

    [com.repldriven.queenswood.registrar.interface :as registrar]

    [com.repldriven.mono.system.interface :as system]))

(def ^:private readiness
  {:system/start (fn [{:system/keys [instance]}] (or instance (atom false)))
   :system/instance-schema some?})

(def ^:private registrar
  {:system/start (fn [{:system/keys [config instance]}]
                   (or instance (registrar/start :onfido config)))
   :system/stop (fn [{:system/keys [instance]}]
                  (when-let [{:keys [stop]} instance] (stop)))
   :system/config {:onfido-url system/required-component
                   :adapter-url system/required-component
                   :readiness system/required-component
                   :record-db system/required-component
                   :record-store system/required-component
                   :delivery-policy system/required-component
                   :retry-ms system/required-component
                   :check-ms system/required-component}
   :system/config-schema registrar/config-schema
   :system/instance-schema map?})

(def ^:private command-processor
  {:system/start (fn [{:system/keys [config instance]}]
                   (or instance (commands/->OnfidoCommandProcessor config)))
   :system/config {:schemas system/required-component
                   :record-db system/required-component
                   :record-store system/required-component}
   :system/instance-schema some?})

(system/defcomponents :onfido-adapter
                      {:readiness readiness
                       :registrar registrar
                       :command-processor command-processor})
