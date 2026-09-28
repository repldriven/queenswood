(ns com.repldriven.queenswood.payment.provider
  (:require
    [com.repldriven.queenswood.payment-provider.interface :as
     payment-provider]))

(defn- entry
  [config]
  (payment-provider/default (:payment-providers config)))

(defn declaration
  [config]
  (:declaration (entry config)))

(defn payment-command-channel
  [config]
  (:payment-command-channel (entry config)))
