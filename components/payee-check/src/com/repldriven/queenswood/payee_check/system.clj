(ns com.repldriven.queenswood.payee-check.system
  (:require
    [com.repldriven.queenswood.payee-check.commands :as commands]

    [com.repldriven.mono.system.interface :as system]))

(def ^:private processor
  {:system/start (fn [{:system/keys [config instance]}]
                   (or instance (commands/->PayeeCheckProcessor config)))
   :system/config {:record-db system/required-component
                   :record-store system/required-component
                   :schemas system/required-component
                   :payment-providers nil
                   :adapter-urls {}}
   :system/instance-schema some?})

(system/defcomponents :payee-check {:processor processor})
