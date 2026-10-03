(ns com.repldriven.queenswood.modulr-adapter.system
  (:require
    [com.repldriven.queenswood.modulr-adapter.commands :as commands]
    [com.repldriven.queenswood.modulr-adapter.provider :as provider]
    [com.repldriven.queenswood.modulr-adapter.subscriptions]

    [com.repldriven.queenswood.registrar.interface :as registrar]

    [com.repldriven.mono.error.interface :refer [let-nom>]]
    [com.repldriven.mono.system.interface :as system]))

(def ^:private readiness
  {:system/start (fn [{:system/keys [instance]}] (or instance (atom false)))
   :system/instance-schema some?})

(def ^:private registrar
  {:system/start (fn [{:system/keys [config instance]}]
                   (or instance (registrar/start :modulr config)))
   :system/stop (fn [{:system/keys [instance]}]
                  (when-let [{:keys [stop]} instance] (stop)))
   :system/config {:modulr-url system/required-component
                   :credentials system/required-component
                   :customer-id system/required-component
                   :webhook-url system/required-component
                   :webhook-credentials system/required-component
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
                   (or instance
                       (let-nom> [_ (provider/check (:payment-provider config))]
                         (commands/->ModulrCommandProcessor config))))
   :system/config {:schemas system/required-component
                   :record-db system/required-component
                   :record-store system/required-component
                   :payment-provider system/required-component
                   :product-code nil}
   :system/instance-schema some?})

(system/defcomponents :modulr-adapter
                      {:readiness readiness
                       :registrar registrar
                       :command-processor command-processor})
