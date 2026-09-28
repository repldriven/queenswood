(ns com.repldriven.queenswood.payment-provider.system
  (:require
    [com.repldriven.queenswood.payment-provider.core :as core]

    [com.repldriven.mono.system.interface :as system]))

(def ^:private declaration
  {:system/start (fn [{:system/keys [config instance]}]
                   (or instance (core/declared config)))
   :system/config {}
   :system/instance-schema map?})

(system/defcomponents :payment-provider {:declaration declaration})
