(ns com.repldriven.queenswood.membership.system
  (:require
    [com.repldriven.queenswood.membership.commands :as commands]

    [com.repldriven.mono.system.interface :as system]))

(def ^:private processor
  {:system/start (fn [{:system/keys [config instance]}]
                   (or instance (commands/->MembershipProcessor config)))
   :system/config {:record-db system/required-component
                   :record-store system/required-component
                   :schemas system/required-component}
   :system/instance-schema some?})

(system/defcomponents :membership {:processor processor})
