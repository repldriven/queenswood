(ns com.repldriven.queenswood.cash-account.system
  (:require
    [com.repldriven.queenswood.cash-account.commands :as commands]
    [com.repldriven.queenswood.cash-account.events :as events]

    [com.repldriven.mono.system.interface :as system]))

(def ^:private processor
  {:system/start (fn [{:system/keys [config instance]}]
                   (or instance (commands/->CashAccountProcessor config)))
   :system/config {:record-db system/required-component
                   :record-store system/required-component
                   :schemas system/required-component}
   :system/instance-schema some?})

(def ^:private event-processor
  {:system/start (fn [{:system/keys [config instance]}]
                   (or instance (events/->CashAccountEventProcessor config)))
   :system/config {:record-db system/required-component
                   :record-store system/required-component
                   :schemas system/required-component
                   :bus system/required-component
                   :scheme-account-command-channel system/required-component}
   :system/instance-schema some?})

(def ^:private payment-account-event-processor
  {:system/start (fn [{:system/keys [config instance]}]
                   (or instance (events/->PaymentAccountEventProcessor config)))
   :system/config {:record-db system/required-component
                   :record-store system/required-component
                   :schemas system/required-component}
   :system/instance-schema some?})

(system/defcomponents :cash-account
                      {:processor processor
                       :event-processor event-processor
                       :payment-account-event-processor
                       payment-account-event-processor})
