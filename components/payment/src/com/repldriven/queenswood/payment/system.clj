(ns com.repldriven.queenswood.payment.system
  (:require
    [com.repldriven.queenswood.payment.commands :as commands]
    [com.repldriven.queenswood.payment.sweep :as sweep]

    [com.repldriven.mono.system.interface :as system]))

(def ^:private default-cutoff {:zone "UTC" :hour-of-day 0})

(def ^:private five-minutes-ms 300000)
(def ^:private fifteen-minutes-ms 900000)
(def ^:private twenty-four-hours-ms 86400000)

(def ^:private processor
  {:system/start (fn [{:system/keys [config instance]}]
                   (or instance (commands/->PaymentProcessor config)))
   :system/config {:record-db system/required-component
                   :record-store system/required-component
                   :schemas system/required-component
                   :bus nil
                   :scheme-payment-command-channel nil
                   :business-day-cutoff default-cutoff}
   :system/instance-schema some?})

(def ^:private event-processor
  {:system/start (fn [{:system/keys [config instance]}]
                   (or instance (commands/->PaymentEventProcessor config)))
   :system/config {:record-db system/required-component
                   :record-store system/required-component
                   :schemas system/required-component
                   :business-day-cutoff default-cutoff}
   :system/instance-schema some?})

(def ^:private outbound-sweep
  {:system/start (fn [{:system/keys [config instance]}]
                   (or instance (sweep/start-runner config)))
   :system/stop (fn [{:system/keys [instance]}]
                  (when-let [{:keys [stop]} instance] (stop)))
   :system/config {:record-db system/required-component
                   :record-store system/required-component
                   :schemas system/required-component
                   :bus system/required-component
                   :scheme-payment-command-channel system/required-component
                   :interval-ms five-minutes-ms
                   :republish-after-ms fifteen-minutes-ms
                   :report-after-ms twenty-four-hours-ms}
   :system/instance-schema map?})

(system/defcomponents :payment
                      {:processor processor
                       :event-processor event-processor
                       :outbound-sweep outbound-sweep})
