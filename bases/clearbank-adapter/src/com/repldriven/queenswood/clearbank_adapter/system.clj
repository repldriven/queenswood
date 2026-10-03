(ns com.repldriven.queenswood.clearbank-adapter.system
  (:require
    [com.repldriven.queenswood.clearbank-adapter.commands
     :as commands]
    [com.repldriven.queenswood.clearbank-adapter.provider :as provider]
    [com.repldriven.queenswood.clearbank-adapter.subscriptions]

    [com.repldriven.queenswood.registrar.interface :as registrar]

    [com.repldriven.mono.error.interface :as error :refer [let-nom>]]
    [com.repldriven.mono.system.interface :as system]))

(def ^:private readiness
  {:system/start (fn [{:system/keys [instance]}] (or instance (atom false)))
   :system/instance-schema some?})

(def ^:private registrar
  {:system/start (fn [{:system/keys [config instance]}]
                   (or instance (registrar/start :clearbank config)))
   :system/stop (fn [{:system/keys [instance]}]
                  (when-let [{:keys [stop]} instance] (stop)))
   :system/config {:simulator-url system/required-component
                   :webhook-url system/required-component
                   :webhooks nil
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
                         (commands/->ClearBankCommandProcessor config))))
   :system/config {:schemas system/required-component
                   :record-db system/required-component
                   :record-store system/required-component
                   :payment-provider system/required-component
                   :sort-code system/required-component}
   :system/instance-schema some?})

(system/defcomponents :clearbank-adapter
                      {:readiness readiness
                       :registrar registrar
                       :command-processor command-processor})
