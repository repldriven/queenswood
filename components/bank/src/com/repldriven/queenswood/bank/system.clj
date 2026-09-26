(ns com.repldriven.queenswood.bank.system
  (:require
    [com.repldriven.queenswood.bank.commands :as commands]

    [com.repldriven.mono.system.interface :as system]))

(def ^:private processor
  {:system/start (fn [{:system/keys [config instance]}]
                   (or instance (commands/->BankProcessor config)))
   :system/config {:record-db system/required-component
                   :record-store system/required-component
                   :schemas system/required-component
                   :identity-provider system/required-component
                   :idv-provider nil}
   :system/instance-schema some?})

(system/defcomponents :bank {:processor processor})
